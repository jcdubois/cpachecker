// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing;

import java.util.List;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.Formula;
import org.sosy_lab.java_smt.api.FormulaType;

/** Interface abstraction for smt heap accesses. */
interface SMTHeap {

  record SMTAddressValue<I extends Formula, E extends Formula>(I address, E value) {}

  /**
   * Create a formula that represents an assignment to a value via a pointer.
   *
   * @param targetName The name of the pointer access symbol as returned by {@link
   *     MemoryRegionManager#getPointerAccessName(MemoryRegion)}
   * @param pTargetType The formula type of the value
   * @param oldIndex The old SSA index for targetName
   * @param newIndex The new SSA index for targetName
   * @param assignments The assignments, each one combining the address where the value should be
   *     written and the value to write
   * @return A formula representing assignments of the form {@code targetName@newIndex[address] =
   *     value}
   */
  <I extends Formula, E extends Formula> BooleanFormula makePointerAssignment(
      final String targetName,
      final FormulaType<?> pTargetType,
      final int oldIndex,
      final int newIndex,
      final List<SMTAddressValue<I, E>> assignments);

  <I extends Formula, E extends Formula> BooleanFormula makeQuantifiedPointerAssignment(
      final String targetName,
      final FormulaType<?> pTargetType,
      final int oldIndex,
      final int newIndex,
      final I address,
      final BooleanFormula condition,
      final E value);

  /**
   * Create a formula that represents an assignment from old SSA index to new SSA index without
   * changing the value. Useful for creating conditional assignments.
   *
   * @param targetName The name of the pointer access symbol as returned by {@link
   *     MemoryRegionManager#getPointerAccessName(MemoryRegion)}
   * @param pTargetType The formula type of the value
   * @param oldIndex The old SSA index for targetName
   * @param newIndex The new SSA index for targetName
   * @return A formula representing an assignment of the form {@code targetName@newIndex[address] =
   *     value}
   */
  <E extends Formula> BooleanFormula makeIdentityPointerAssignment(
      final String targetName,
      final FormulaType<E> pTargetType,
      final int oldIndex,
      final int newIndex);

  /**
   * Make a formula that represents a pointer access.
   *
   * @param targetName The name of the pointer access symbol as returned by {@link
   *     MemoryRegionManager#getPointerAccessName(MemoryRegion)}
   * @param targetType The formula type of the value
   * @param address The address to access
   * @return A formula representing {@code targetName@ssaIndex[address]}
   */
  <I extends Formula, E extends Formula> E makePointerDereference(
      final String targetName, final FormulaType<E> targetType, final I address);

  /**
   * Make a formula that represents a pointer access.
   *
   * @param targetName The name of the pointer access symbol as returned by {@link
   *     MemoryRegionManager#getPointerAccessName(MemoryRegion)}
   * @param targetType The formula type of the value
   * @param address The address to access
   * @return A formula representing {@code targetName[address]}
   */
  <I extends Formula, V extends Formula> V makePointerDereference(
      final String targetName,
      final FormulaType<V> targetType,
      final int ssaIndex,
      final I address);
}
