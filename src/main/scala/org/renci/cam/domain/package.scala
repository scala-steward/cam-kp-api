package org.renci.cam

import com.google.common.base.CaseFormat
import contextual.Case
import org.apache.jena.query.{ParameterizedSparqlString, QuerySolution}
import org.phenoscape.sparql.FromQuerySolution
import org.phenoscape.sparql.SPARQLInterpolation.SPARQLInterpolator
import org.phenoscape.sparql.SPARQLInterpolation.SPARQLInterpolator.SPARQLContext

import scala.util.Try

package object domain {

  case class IRI(value: String)

  object IRI {

    implicit val embedInSPARQL = SPARQLInterpolator.embed[IRI](Case(SPARQLContext, SPARQLContext) { iri =>
      val pss = new ParameterizedSparqlString()
      pss.appendIri(iri.value)
      pss.toString
    })

    implicit object IRIFromQuerySolution extends FromQuerySolution[IRI] {

      def fromQuerySolution(qs: QuerySolution, variablePath: String = ""): Try[IRI] =
        getResource(qs, variablePath).map(r => IRI(r.getURI))

    }

  }

  sealed trait BiolinkTerm {

    def shorthand: String

    def iri: IRI

  }

  final case class BiolinkClass(shorthand: String, iri: IRI) extends BiolinkTerm

  object BiolinkClass {

    def apply(label: String): BiolinkClass = {
      val camelLabel = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, label)
      BiolinkClass(label, IRI(s"${BiolinkTerm.namespace}$camelLabel"))
    }

  }

  final case class BiolinkPredicate(shorthand: String, iri: IRI) extends BiolinkTerm

  object BiolinkPredicate {

    def apply(label: String): BiolinkPredicate = BiolinkPredicate(label, IRI(s"${BiolinkTerm.namespace}$label"))

  }

  object BiolinkTerm {

    val namespace: String = "https://w3id.org/biolink/vocab/"

  }

  case class TRAPIQueryNode(id: String, `type`: Option[BiolinkClass], curie: Option[IRI])

  case class TRAPIQueryEdge(id: String, source_id: String, target_id: String, `type`: Option[BiolinkPredicate])

  case class TRAPIQueryGraph(nodes: List[TRAPIQueryNode], edges: List[TRAPIQueryEdge])

  case class TRAPINode(id: String, name: Option[String], `type`: List[BiolinkClass])

  case class TRAPIEdge(id: String, source_id: IRI, target_id: IRI, `type`: Option[BiolinkPredicate])

  case class TRAPIKnowledgeGraph(nodes: List[TRAPINode], edges: List[TRAPIEdge])

  case class TRAPINodeBinding(qg_id: Option[String], kg_id: String)

  case class TRAPIEdgeBinding(qg_id: Option[String], kg_id: String, provenance: Option[String])

  case class TRAPIResult(node_bindings: List[TRAPINodeBinding],
                         edge_bindings: List[TRAPIEdgeBinding],
                         extra_nodes: Option[List[TRAPINodeBinding]],
                         extra_edges: Option[List[TRAPIEdgeBinding]])

  case class TRAPIMessage(query_graph: Option[TRAPIQueryGraph],
                          knowledge_graph: Option[TRAPIKnowledgeGraph],
                          results: Option[List[TRAPIResult]])

  case class TRAPIQueryRequestBody(message: TRAPIMessage)

}
