/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v4_0.planner.logical

import org.neo4j.cypher.internal.compiler.v4_0.planner.LogicalPlanningTestSupport2
import org.neo4j.cypher.internal.compiler.v4_0.planner.logical.Metrics.QueryGraphSolverInput
import org.neo4j.cypher.internal.ir.v4_0.LazyMode
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes.Cardinalities
import org.neo4j.cypher.internal.v4_0.logical.plans._
import org.neo4j.cypher.internal.v4_0.expressions.SemanticDirection
import org.neo4j.cypher.internal.v4_0.util.Cost
import org.neo4j.cypher.internal.v4_0.util.test_helpers.CypherFunSuite

class CardinalityCostModelTest extends CypherFunSuite with LogicalPlanningTestSupport2 {

  test("expand should only be counted once") {
    val cardinalities = new Cardinalities
    val plan =
      setC(Selection(ands(hasLabels("a", "Awesome")),
        setC(Expand(
          setC(Selection(ands(hasLabels("a", "Awesome")),
                         setC(Expand(
              setC(Argument(Set("a")), cardinalities, 10.0),
              "a", SemanticDirection.OUTGOING, Seq.empty, "b", "r1"), cardinalities, 100.0)
          ), cardinalities, 10.0), "a", SemanticDirection.OUTGOING, Seq.empty, "b", "r1"), cardinalities, 100.0)
      ), cardinalities, 10.0)

    costFor(plan, QueryGraphSolverInput.empty, cardinalities) should equal(Cost(231))
  }

  test("should introduce increase cost when estimating an eager operator and laziness is preferred") {
    val cardinalities = new Cardinalities
    val plan = setC(NodeHashJoin(Set("a"),
      setC(NodeByLabelScan("a", labelName("A"), Set.empty), cardinalities, 10.0),
      setC(Expand(
        setC(NodeByLabelScan("b", labelName("B"), Set.empty), cardinalities, 5.0),
        "b", SemanticDirection.OUTGOING, Seq.empty, "a", "r", ExpandAll), cardinalities, 15.0)
    ), cardinalities, 10.0)

    val pleaseLazy = QueryGraphSolverInput.empty.withPreferredStrictness(LazyMode)
    val whatever = QueryGraphSolverInput.empty

    costFor(plan, whatever, cardinalities) should be < costFor(plan, pleaseLazy, cardinalities)
  }

  test("non-lazy plan should be penalized when estimating cost wrt a lazy one when laziness is preferred") {
    // MATCH (a1: A)-[r1]->(b)<-[r2]-(a2: A) RETURN b
    val lazyCardinalities = new Cardinalities
    val lazyPlan = setC(Projection(
      setC(Selection(
        Seq(hasLabels("a2", "A")),
        setC(Expand(
          setC(Expand(
            setC(NodeByLabelScan("a1", labelName("A"), Set.empty), lazyCardinalities, 10.0),
            "a1", SemanticDirection.OUTGOING, Seq.empty, "b", "r1", ExpandAll
          ), lazyCardinalities, 50.0),
          "b", SemanticDirection.INCOMING, Seq.empty, "a2", "r2", ExpandAll
        ), lazyCardinalities, 250.0)
      ), lazyCardinalities, 250.0), Map("b" -> varFor("b"))
    ), lazyCardinalities, 250.0)

    val eagerCardinalities = new Cardinalities
    val eagerPlan = setC(Projection(
      setC(NodeHashJoin(Set("b"),
        setC(Expand(
          setC(NodeByLabelScan("a1", labelName("A"), Set.empty), eagerCardinalities, 10.0),
          "a1", SemanticDirection.OUTGOING, Seq.empty, "b", "r1", ExpandAll
        ), eagerCardinalities, 50.0),
        setC(Expand(
          setC(NodeByLabelScan("a2", labelName("A"), Set.empty), eagerCardinalities, 10.0),
          "a2", SemanticDirection.OUTGOING, Seq.empty, "b", "r2", ExpandAll
        ), eagerCardinalities, 50.0)
      ), eagerCardinalities, 250.0), Map("b" -> varFor("b"))
    ), eagerCardinalities, 250.0)

    val whatever = QueryGraphSolverInput.empty
    costFor(lazyPlan, whatever, lazyCardinalities) should be > costFor(eagerPlan, whatever, eagerCardinalities)

    val pleaseLazy = QueryGraphSolverInput.empty.withPreferredStrictness(LazyMode)
    costFor(lazyPlan, pleaseLazy, lazyCardinalities) should be < costFor(eagerPlan, pleaseLazy, eagerCardinalities)
  }

  test("multiple property expressions are counted for in cost") {
    val cardinalities = new Cardinalities
    val cardinality = 10.0
    val plan =
      setC(Selection(List(propEquality("a", "prop1", 42), propEquality("a", "prop1", 43), propEquality("a", "prop1", 44)),
        setC(Argument(Set("a")), cardinalities, cardinality)), cardinalities, cardinality)

    val numberOfPredicates = 3
    val costForSelection = cardinality * numberOfPredicates
    val costForArgument = cardinality * .1
    costFor(plan, QueryGraphSolverInput.empty, cardinalities) should equal(Cost(costForSelection + costForArgument))
  }

  private def costFor(plan: LogicalPlan,
                      input: QueryGraphSolverInput = QueryGraphSolverInput.empty,
                      cardinalities: Cardinalities) = {
    CardinalityCostModel(cypherCompilerConfig).apply(plan, input, cardinalities)
  }

}
