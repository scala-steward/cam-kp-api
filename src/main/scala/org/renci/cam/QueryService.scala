package org.renci.cam

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import io.circe.syntax._
import org.apache.commons.lang3.StringUtils
import org.apache.jena.ext.com.google.common.base.CaseFormat
import org.apache.jena.query.{QueryFactory, QuerySolution, ResultSet}
import org.phenoscape.sparql.SPARQLInterpolation._
import org.renci.cam.Biolink._
import org.renci.cam.HttpClient.HttpClient
import org.renci.cam.domain._
import zio.config.ZConfig
import zio.{Has, RIO, Task, ZIO, config => _}

import scala.collection.JavaConverters._

object QueryService extends LazyLogging {

  val provWasDerivedFrom: IRI = IRI("http://www.w3.org/ns/prov#wasDerivedFrom")

  val rdfSchemaSubClassOf: IRI = IRI("http://www.w3.org/2000/01/rdf-schema#subClassOf")

  val rdfSchemaLabel: IRI = IRI("http://www.w3.org/2000/01/rdf-schema#label")

  val rdfSyntaxType: IRI = IRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

  val owlNamedIndividual: IRI = IRI("http://www.w3.org/2002/07/owl#NamedIndividual")

  val sesameDirectType: IRI = IRI("http://www.openrdf.org/schema/sesame#directType")

  val biolinkmlSlotDefinition: IRI = IRI("https://w3id.org/biolink/biolinkml/meta/types/SlotDefinition")

  val biolinkmlIsA: IRI = IRI("https://w3id.org/biolink/biolinkml/meta/is_a")

  val NamedThing: IRI = IRI("https://w3id.org/biolink/vocab/NamedThing")

  val rdfSchemaSubPropertyOf: IRI = IRI("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")

  final case class TRAPIEdgeKey(`type`: Option[BiolinkPredicate], source_id: String, target_id: String)

  final case class Triple(subj: IRI, pred: IRI, obj: IRI)

  final case class TripleString(subj: String, pred: String, obj: String)

  final case class SlotStuff(qid: String, kid: IRI, biolinkSlot: IRI, label: Option[String])

  // instances are not thread-safe; should be retrieved for every use
  private def messageDigest: MessageDigest = MessageDigest.getInstance("SHA-256")

  private def convertCase(v: String): String = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, v)

  def getNodeTypes(nodes: List[TRAPIQueryNode]): Map[String, IRI] =
    nodes.flatMap { node =>
      (node.`type`, node.curie) match {
        case (_, Some(c))    => List(node.id -> c)
        case (Some(t), None) => List(node.id -> t.iri)
        case (None, None)    => Nil
      }
    }.toMap

  def applyPrefix(value: String, prefixes: Map[String, String]): String =
    prefixes
      .filter(entry => value.startsWith(entry._2))
      .map(entry => StringUtils.prependIfMissing(value.substring(entry._2.length, value.length), s"${entry._1}:"))
      .headOption
      .getOrElse(value)

  private def queryEdgePredicates(
    edge: TRAPIQueryEdge): RIO[ZConfig[AppConfig] with HttpClient, (Set[String], Set[(String, String)], String)] =
    for {
      edgeType <- ZIO.fromOption(edge.`type`).orElseFail(new Exception("failed to get edge type"))
      queryText =
        sparql"""SELECT DISTINCT ?predicate WHERE { ?predicate $rdfSchemaSubPropertyOf+ ${edgeType.iri} . FILTER NOT EXISTS { ?predicate a $biolinkmlSlotDefinition } }"""
      resultSet <- SPARQLQueryExecutor.runSelectQuery(queryText.toQuery)
      querySolutions = resultSet.asScala.toList
      //FIXME getResource can throw exceptions
      predicates = querySolutions
        .flatMap(qs => qs.varNames.asScala.map(v => qs.getResource(v).getURI))
        .map(a => IRI(a))
        .map(a => sparql"$a".text)
        .mkString(" ")
      predicateValuesBlock = s"VALUES ?${edge.id} { $predicates }"
      triple = s"  ?${edge.source_id} ?${edge.id} ?${edge.target_id} ."
    } yield (Set(edge.source_id, edge.target_id),
             Set(edge.source_id -> edge.source_id, edge.target_id -> edge.target_id),
             s"$predicateValuesBlock\n$triple")

  def run(limit: Int, queryGraph: TRAPIQueryGraph): RIO[ZConfig[AppConfig] with HttpClient with Has[BiolinkData], ResultSet] = {
    val nodeTypes = getNodeTypes(queryGraph.nodes)
    for {
      predicates <- ZIO.foreachPar(queryGraph.edges.filter(_.`type`.nonEmpty))(queryEdgePredicates)
      (instanceVars, instanceVarsToTypes, sparqlLines) = predicates.unzip3
      whereClauseParts =
        queryGraph.nodes
          .map { node =>
            val nodeIdQueryText = QueryText(s"${node.id}")
            sparql"  ?$nodeIdQueryText $sesameDirectType ?${nodeIdQueryText}_type .".text
          }
          .mkString("\n")
      whereClause = s"WHERE { \n$whereClauseParts"
      ids =
        instanceVars.toSet.flatten.map(a => s"?$a").toList :::
          queryGraph.nodes.map(a => s"?${a.id}_type") :::
          queryGraph.edges.map(a => s"?${a.id}")
      selectClause = s"SELECT DISTINCT ${ids.mkString(" ")} "
      moreLines = for {
        (subj, typ) <- instanceVarsToTypes.flatten
        v <- nodeTypes.get(typ)
      } yield {
        val subjQueryText = QueryText(s"$subj")
        sparql"?$subjQueryText $rdfSyntaxType $v .".text
      }
      valuesClause = (sparqlLines ++ moreLines).mkString("\n")
      limitSparql = if (limit > 0) s" LIMIT $limit" else ""
      queryString = s"""$selectClause\n$whereClause\n$valuesClause \n } $limitSparql"""
      response <- SPARQLQueryExecutor.runSelectQuery(QueryFactory.create(queryString))
    } yield response
  }

  def parseResultSet(queryGraph: TRAPIQueryGraph,
                     resultSet: ResultSet): RIO[ZConfig[AppConfig] with HttpClient with Has[BiolinkData], TRAPIMessage] =
    for {
      biolinkData <- biolinkData
      querySolutions = resultSet.asScala.toList
      initialKGNodes <- getTRAPINodes(queryGraph, querySolutions)
      initialKGEdges <- getTRAPIEdges(queryGraph, querySolutions)
      querySolutionsToEdgeBindings <- getTRAPIEdgeBindingsMany(queryGraph, querySolutions)
      trapiBindings <- ZIO.foreach(querySolutions) { querySolution =>
        getTRAPINodeBindings(queryGraph, querySolution, biolinkData.prefixes) zip Task.effect(querySolutionsToEdgeBindings(querySolution))
      }
      allEdgeBindings = trapiBindings.flatMap(_._2)
      allCamIds = allEdgeBindings.toSet[TRAPIEdgeBinding].flatMap(_.provenance)
      prov2CAMStuffTripleMap <- ZIO.foreachPar(allCamIds)(prov => getCAMStuff(IRI(prov)).map(prov -> _)).map(_.toMap)
      allCAMTriples = prov2CAMStuffTripleMap.values.toSet.flatten
      allTripleNodes = allCAMTriples.flatMap(t => Set(t.subj, t.obj))
      slotStuffNodeDetails <- getTRAPINodeDetails(allTripleNodes.toList, biolinkData.classes)
      extraKGNodes = getExtraKGNodes(allTripleNodes, slotStuffNodeDetails, biolinkData)
      allPredicates = allCAMTriples.map(_.pred)
      slotStuffList <- getSlotStuff(allPredicates.toList)
      extraKGEdges = allCAMTriples.flatMap { triple =>
        for {
          slotStuff <- slotStuffList.find(_.kid == triple.pred)
          predBLTermOpt = biolinkData.predicates.find(a => a.iri == slotStuff.biolinkSlot)
        } yield {
          val edgeKey =
            TRAPIEdgeKey(predBLTermOpt, triple.subj.value, triple.obj.value).asJson.deepDropNullValues.noSpaces
          val knowledgeGraphId = String.format("%064x", new BigInteger(1, messageDigest.digest(edgeKey.getBytes(StandardCharsets.UTF_8))))
          TRAPIEdge(knowledgeGraphId, triple.subj, triple.obj, predBLTermOpt)
        }
      }
      results <- ZIO.foreach(trapiBindings) { case (resultNodeBindings, resultEdgeBindings) =>
        val provsAndCamTriples =
          resultEdgeBindings.flatMap(_.provenance).map(prov => prov -> prov2CAMStuffTripleMap.get(prov).toSet.flatten).toMap
        val nodes = provsAndCamTriples.values.flatten.toSet[Triple].flatMap(t => Set(t.subj, t.obj))
        val extraKGNodeBindings = nodes.map(n => TRAPINodeBinding(None, applyPrefix(n.value, biolinkData.prefixes)))
        ZIO
          .foreach(provsAndCamTriples.toIterable) { case (prov, triples) =>
            ZIO.foreach(triples) { triple =>
              for {
                predBLTermOpt <- ZIO.effectTotal(biolinkData.predicates.find(a => a.iri == triple.pred))
              } yield {
                val edgeKey = TRAPIEdgeKey(predBLTermOpt, triple.subj.value, triple.obj.value).asJson.deepDropNullValues.noSpaces
                val kgId = String.format("%064x", new BigInteger(1, messageDigest.digest(edgeKey.getBytes(StandardCharsets.UTF_8))))
                TRAPIEdgeBinding(None, kgId, Some(prov))
              }
            }
          }
          .map(_.flatten.toSet)
          .map { extraKGEdgeBindings =>
            TRAPIResult(resultNodeBindings, resultEdgeBindings, Some(extraKGNodeBindings.toList), Some(extraKGEdgeBindings.toList))
          }

      }
      trapiKGNodes = initialKGNodes ++ extraKGNodes
      trapiKGEdges = initialKGEdges ++ extraKGEdges
    } yield TRAPIMessage(Some(queryGraph), Some(TRAPIKnowledgeGraph(trapiKGNodes.distinct, trapiKGEdges)), Some(results))

  private def getTRAPINodes(
    queryGraph: TRAPIQueryGraph,
    querySolutions: List[QuerySolution]): RIO[ZConfig[AppConfig] with HttpClient with Has[BiolinkData], List[TRAPINode]] = {
    val allOntClassIRIsZ = ZIO
      .foreach(querySolutions) { qs =>
        ZIO.foreach(qs.varNames.asScala.filter(_.endsWith("_type")).toIterable) { typeVar =>
          ZIO.effect(IRI(qs.getResource(typeVar).getURI)).mapError { e =>
            new Exception(s"Value of _type variable $typeVar is not a URI", e)
          }
        }
      }
      .map(_.flatten)
    for {
      allOntClassIRIs <- allOntClassIRIsZ
      biolinkData <- biolinkData
      nodeDetails <- getTRAPINodeDetails(allOntClassIRIs, biolinkData.classes)
      termToLabelAndTypes = nodeDetails.groupBy(_.term).map { case (term, termsAndTypes) =>
        val (labels, biolinkTypes) = termsAndTypes.map(t => t.label -> t.biolinkType).unzip
        term -> (labels.flatten.headOption, biolinkTypes)
      }
      trapiNodes <- ZIO.foreach(querySolutions) { querySolution =>
        for {
          nodeMap <- Task.effect(queryGraph.nodes.map(n => (n.id, querySolution.get(s"${n.id}_type").toString)).toMap)
          nodes <- ZIO.foreach(queryGraph.nodes) { n =>
            for {
              nodeIRI <- ZIO.fromOption(nodeMap.get(n.id)).orElseFail(new Exception(s"Missing node IRI: ${n.id}"))
              labelAndTypes = termToLabelAndTypes.getOrElse(IRI(nodeIRI), (None, List(NamedThing)))
              (labelOpt, biolinkTypes) = labelAndTypes
              biolinkTypesSet = biolinkTypes.toSet
              nodeBiolinkTypes = biolinkData.classes.filter(c => biolinkTypesSet(c.iri))
            } yield TRAPINode(applyPrefix(nodeIRI, biolinkData.prefixes), labelOpt, nodeBiolinkTypes)
          }
        } yield nodes
      }
    } yield trapiNodes.flatten
  }

  private def getTRAPIEdges(queryGraph: TRAPIQueryGraph,
                            querySolutions: List[QuerySolution]): RIO[ZConfig[AppConfig] with HttpClient, List[TRAPIEdge]] =
    for {
      trapiEdges <- ZIO.foreach(querySolutions) { querySolution =>
        for {
          nodeMap <- Task.effect(queryGraph.nodes.map(n => (n.id, IRI(querySolution.getResource(s"${n.id}_type").getURI))).toMap)
          edges <- ZIO.foreach(queryGraph.edges) { e =>
            for {
              sourceId <- ZIO.fromOption(nodeMap.get(e.source_id)).orElseFail(new Exception("could not get source id"))
              targetId <- ZIO.fromOption(nodeMap.get(e.target_id)).orElseFail(new Exception("could not get target id"))
              edgeKey = TRAPIEdgeKey(e.`type`, e.source_id, e.target_id).asJson.deepDropNullValues.noSpaces
              encodedTRAPIEdge = String.format("%064x", new BigInteger(1, messageDigest.digest(edgeKey.getBytes(StandardCharsets.UTF_8))))
              trapiEdge = TRAPIEdge(encodedTRAPIEdge, sourceId, targetId, e.`type`)
            } yield trapiEdge
          }
        } yield edges
      }
    } yield trapiEdges.flatten

  private def getTRAPINodeBindings(queryGraph: TRAPIQueryGraph,
                                   querySolution: QuerySolution,
                                   prefixes: Map[String, String]): RIO[ZConfig[AppConfig], List[TRAPINodeBinding]] =
    for {
      nodeMap <- Task.effect(queryGraph.nodes.map(n => (n.id, querySolution.get(s"${n.id}_type").toString)).toMap)
      nodeBindings <- ZIO.foreach(queryGraph.nodes) { n =>
        for {
          nodeIRI <- ZIO.fromOption(nodeMap.get(n.id)).orElseFail(new Exception(s"Missing node IRI: ${n.id}"))
          nodeBinding <- Task.effect(TRAPINodeBinding(Some(n.id), applyPrefix(nodeIRI, prefixes)))
        } yield nodeBinding
      }
    } yield nodeBindings

  private def getTRAPIEdgeBindingsMany(queryGraph: TRAPIQueryGraph, querySolutions: List[QuerySolution])
    : ZIO[ZConfig[AppConfig] with HttpClient with Has[BiolinkData], Throwable, Map[QuerySolution, List[TRAPIEdgeBinding]]] = {
    val queryTriples = queryGraph.edges.map(e => TripleString(e.source_id, e.id, e.target_id))
    val solutionTriples = querySolutions.flatMap { qs =>
      queryTriples.map { qt =>
        TripleString(qs.getResource(qt.subj).getURI, qs.getResource(qt.pred).getURI, qs.getResource(qt.obj).getURI)
      }
    }
    for {
      provs <- getProvenance(solutionTriples.toSet)
      querySolutionsToEdgeBindings <- ZIO.foreach(querySolutions) { querySolution =>
        for {
          edgeBindings <- ZIO.foreach(queryGraph.edges) { e =>
            for {
              predicateRDFNode <- Task.effect(querySolution.get(e.id).toString)
              sourceRDFNode <- Task.effect(querySolution.get(e.source_id).toString)
              targetRDFNode <- Task.effect(querySolution.get(e.target_id).toString)
              edgeKey = TRAPIEdgeKey(e.`type`, e.source_id, e.target_id).asJson.deepDropNullValues.noSpaces.getBytes(StandardCharsets.UTF_8)
              encodedTRAPIEdge = String.format("%064x", new BigInteger(1, messageDigest.digest(edgeKey)))
              prov <-
                ZIO
                  .fromOption(provs.get(TripleString(sourceRDFNode, predicateRDFNode, targetRDFNode)))
                  .orElseFail(new Exception("Unexpected triple string"))
              trapiEdgeBinding = TRAPIEdgeBinding(Some(e.id), encodedTRAPIEdge, Some(prov))
            } yield trapiEdgeBinding
          }
        } yield querySolution -> edgeBindings
      }
    } yield querySolutionsToEdgeBindings.toMap
  }

  private def getExtraKGNodes(camNodes: Set[IRI],
                              slotStuffNodeDetails: List[TermWithLabelAndBiolinkType],
                              biolinkData: BiolinkData): Set[TRAPINode] = {
    val termToLabelAndTypes = slotStuffNodeDetails.groupBy(_.term).map { case (term, termsAndTypes) =>
      val (labels, biolinkTypes) = termsAndTypes.map(t => t.label -> t.biolinkType).unzip
      term -> (labels.flatten.headOption, biolinkTypes)
    }
    camNodes.map { node =>
      val (labelOpt, biolinkTypes) = termToLabelAndTypes.getOrElse(node, (None, List(NamedThing)))
      val biolinkTypesSet = biolinkTypes.toSet
      val abbreviatedNodeType = applyPrefix(node.value, biolinkData.prefixes)
      val classes = biolinkData.classes.filter(c => biolinkTypesSet(c.iri))
      TRAPINode(abbreviatedNodeType, labelOpt, classes)
    }
  }

  private def getProvenance(edges: Set[TripleString]): ZIO[ZConfig[AppConfig] with HttpClient, Throwable, Map[TripleString, String]] =
    for {
      values <- Task.effect(QueryText(edges.map(e => s"(<${e.subj}> <${e.pred}> <${e.obj}>)").mkString(" ")))
      queryText =
        sparql"""SELECT ?s ?p ?o ?g ?other WHERE { VALUES (?s ?p ?o) { $values } GRAPH ?g { ?s ?p ?o } OPTIONAL { ?g $provWasDerivedFrom ?other . } }"""
      bindings <- SPARQLQueryExecutor.runSelectQuery(queryText.toQuery)
      triplesToGraphs <- ZIO.foreach(bindings.asScala.toList) { solution =>
        Task.effect {
          val graph = if (solution.contains("other")) solution.getResource("other").getURI else solution.getResource("g").getURI
          val triple = TripleString(solution.getResource("s").getURI, solution.getResource("p").getURI, solution.getResource("o").getURI)
          triple -> graph
        }
      }
    } yield triplesToGraphs.toMap

  final private case class TermWithLabelAndBiolinkType(term: IRI, biolinkType: IRI, label: Option[String])

  private def getTRAPINodeDetails(
    nodeIdList: List[IRI],
    biolinkClasses: List[BiolinkClass]): RIO[ZConfig[AppConfig] with HttpClient, List[TermWithLabelAndBiolinkType]] = {
    val nodeIds = nodeIdList.map(n => sparql" $n ").fold(sparql"")(_ + _)
    for {
      namedThingBiolinkClass <- ZIO
        .fromOption(biolinkClasses.find(a => a.shorthand == "named_thing"))
        .orElseFail(new Exception("Could not find BiolinkClass:NamedThing"))
      //FIXME problem - requiring biolinkType makes some nodes not be found when these results are used elsewhere
      queryText = sparql"""
                     SELECT ?term ?biolinkType (MIN(?term_label) AS ?label)
                     WHERE { 
                       VALUES ?term { $nodeIds }
                       ?term $rdfSchemaSubClassOf ?biolinkType .
                       ?biolinkType $biolinkmlIsA* ${namedThingBiolinkClass.iri} .
                       OPTIONAL { ?term $rdfSchemaLabel ?term_label }
                     }
                     GROUP BY ?term ?biolinkType
                     """
      termsAndBiolinkTypes <- SPARQLQueryExecutor.runSelectQueryAs[TermWithLabelAndBiolinkType](queryText.toQuery)
    } yield termsAndBiolinkTypes
  }

  private def getCAMStuff(prov: IRI): RIO[ZConfig[AppConfig] with HttpClient, List[Triple]] =
    for {
      queryText <- Task.effect(
        sparql"""SELECT DISTINCT (?s_type AS ?subj) (?p AS ?pred) (?o_type AS ?obj) WHERE { GRAPH $prov {
                   ?s ?p ?o .
                   ?s $rdfSyntaxType $owlNamedIndividual .
                   ?o $rdfSyntaxType $owlNamedIndividual .
                 }
                 ?o $sesameDirectType ?o_type .
                 ?s $sesameDirectType ?s_type .
                 FILTER(isIRI(?o_type))
                 FILTER(isIRI(?s_type))
               }"""
      )
      triples <- SPARQLQueryExecutor.runSelectQueryAs[Triple](queryText.toQuery)
    } yield triples

  private def getSlotStuff(predicates: List[IRI]): RIO[ZConfig[AppConfig] with HttpClient, List[SlotStuff]] = {
    val values = predicates.zipWithIndex
      .map { case (p, i) =>
        val id = StringUtils.leftPad(i.toString, 4, '0')
        val qid = s"e$id"
        sparql" ( $p $qid ) "
      }
      .fold(sparql"")(_ + _)
    val queryText = sparql"""
                     SELECT DISTINCT ?qid ?kid ?biolinkSlot ?label 
                     WHERE { 
                     VALUES (?kid ?qid) { $values }
                     ?kid $rdfSchemaSubPropertyOf+ ?biolinkSlot .
                     ?biolinkSlot a $biolinkmlSlotDefinition .
                     OPTIONAL { ?kid $rdfSchemaLabel ?label . }
                     FILTER NOT EXISTS {
                       ?kid $rdfSchemaSubPropertyOf+ ?other .
                       ?other <https://w3id.org/biolink/biolinkml/meta/is_a>+/<https://w3id.org/biolink/biolinkml/meta/mixins>* ?biolinkSlot .
                       } 
                     }
                     """
    SPARQLQueryExecutor.runSelectQueryAs[SlotStuff](queryText.toQuery)
  }

}
