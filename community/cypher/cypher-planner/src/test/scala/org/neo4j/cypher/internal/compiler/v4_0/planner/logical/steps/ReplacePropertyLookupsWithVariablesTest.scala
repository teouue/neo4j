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
package org.neo4j.cypher.internal.compiler.v4_0.planner.logical.steps

import org.neo4j.cypher.internal.compiler.v4_0.phases.LogicalPlanState
import org.neo4j.cypher.internal.compiler.v4_0.phases.PlannerContext
import org.neo4j.cypher.internal.compiler.v4_0.planner.LogicalPlanConstructionTestSupport
import org.neo4j.cypher.internal.compiler.v4_0.planner.logical.PlanMatchHelp
import org.neo4j.cypher.internal.planner.spi.IDPPlannerName
import org.neo4j.cypher.internal.v4_0.logical.plans.{LogicalPlan, NodeHashJoin, Selection}
import org.neo4j.cypher.internal.v4_0.ast.ASTAnnotationMap
import org.neo4j.cypher.internal.v4_0.ast.semantics.ExpressionTypeInfo
import org.neo4j.cypher.internal.v4_0.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.v4_0.expressions._
import org.neo4j.cypher.internal.v4_0.frontend.phases.InitialState
import org.neo4j.cypher.internal.v4_0.util.InputPosition
import org.neo4j.cypher.internal.v4_0.util.symbols._
import org.neo4j.cypher.internal.v4_0.util.test_helpers.CypherFunSuite

class ReplacePropertyLookupsWithVariablesTest extends CypherFunSuite with PlanMatchHelp with LogicalPlanConstructionTestSupport {
  // Have specific input positions to test semantic table (not DummyPosition)
  private val variable = Variable("n")(InputPosition.NONE)
  private val propertyKeyName = PropertyKeyName("prop")(InputPosition.NONE)
  private val property = Property(variable, propertyKeyName)(InputPosition.NONE)

  private val newCachedNodeProperty = cachedNodeProperty("n", "prop")

  test("should rewrite prop(n, prop) to CachedNodeProperty(n.prop)") {
    val initialTable = semanticTable(property -> CTInteger)
    val plan = Selection(
      Seq(propEquality("n", "prop", 1)),
      nodeIndexScan("n", "L", "prop")
    )
    val (newPlan, newTable) = replace(plan, initialTable)

    newPlan should equal(
      Selection(
        Seq(equals(newCachedNodeProperty, literalInt(1))),
        nodeIndexScan("n", "L", "prop")
      )
    )
    newTable.types(property) should equal(newTable.types(newCachedNodeProperty))
  }

  test("should rewrite [prop(n, prop)] to [CachedNodeProperty(n.prop)]") {
    val initialTable = semanticTable(property -> CTInteger)
    val plan = Selection(
      Seq(equals(listOf(prop("n", "prop")), listOfInt(1))),
      nodeIndexScan("n", "L", "prop")
    )
    val (newPlan, newTable) = replace(plan, initialTable)

    newPlan should equal(
      Selection(
        Seq(equals(listOf(newCachedNodeProperty), listOfInt(1))),
        nodeIndexScan("n", "L", "prop")
      )
    )
    newTable.types(property) should equal(newTable.types(newCachedNodeProperty))
  }

  test("should rewrite {foo: prop(n, prop)} to {foo: CachedNodeProperty(n.prop)}") {
    val initialTable = semanticTable(property -> CTInteger)
    val plan = Selection(
      Seq(equals(mapOf("foo" -> prop("n", "prop")), mapOfInt(("foo", 1)))),
      nodeIndexScan("n", "L", "prop")
    )
    val (newPlan, newTable) = replace(plan, initialTable)

    newPlan should equal(
      Selection(
        Seq(equals(mapOf("foo" -> newCachedNodeProperty), mapOfInt(("foo", 1)))),
        nodeIndexScan("n", "L", "prop")
      )
    )
    newTable.types(property) should equal(newTable.types(newCachedNodeProperty))
  }

  test("should not explode on missing type info") {
    val initialTable = semanticTable(property -> CTInteger)
    val propWithoutSemanticType = propEquality("m", "prop", 2)

    val plan = Selection(
      Seq(propEquality("n", "prop", 1), propWithoutSemanticType),
      NodeHashJoin(Set("n"),
        nodeIndexScan("n", "L", "prop"),
        nodeIndexScan("m", "L", "prop")
      )
    )
    val (_, newTable) = replace(plan, initialTable)
    newTable.types(property) should equal(newTable.types(newCachedNodeProperty))
  }

  private def replace(plan: LogicalPlan, initialTable: SemanticTable): (LogicalPlan, SemanticTable) = {
    val state = LogicalPlanState(InitialState("", None, IDPPlannerName)).withSemanticTable(initialTable).withMaybeLogicalPlan(Some(plan))
    val resultState = replacePropertyLookupsWithVariables.transform(state, mock[PlannerContext])
    (resultState.logicalPlan, resultState.semanticTable())
  }

  private def semanticTable(types:(Expression, TypeSpec)*): SemanticTable = {
    val mappedTypes = types.map { case(expr, typeSpec) => expr -> ExpressionTypeInfo(typeSpec)}
    SemanticTable(types = ASTAnnotationMap[Expression, ExpressionTypeInfo](mappedTypes:_*))
  }
}
