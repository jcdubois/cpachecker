// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CCastExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIntegerLiteralExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CLeftHandSide;
import org.sosy_lab.cpachecker.cfa.ast.c.CRightHandSide;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.types.c.CPointerType;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.CTypes;
import org.sosy_lab.cpachecker.exceptions.UnrecognizedCodeException;
import org.sosy_lab.cpachecker.util.predicates.pathformula.ErrorConditions;
import org.sosy_lab.cpachecker.util.predicates.pathformula.SSAMap.SSAMapBuilder;
import org.sosy_lab.cpachecker.util.predicates.pathformula.ctoformula.Constraints;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.ArraySliceExpression.ArraySliceIndexVariable;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.ArraySliceExpression.ArraySliceResolved;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.AssignmentHandler.ArraySliceSpanLhs;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.AssignmentHandler.ArraySliceSpanResolved;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.AssignmentHandler.ArraySliceSpanRhs;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.AssignmentHandler.AssignmentOptions;
import org.sosy_lab.cpachecker.util.predicates.smt.BooleanFormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.Formula;
import org.sosy_lab.java_smt.api.FormulaType;

/**
 * Handles resolving assignments that have been already made simple. The quantified variables are
 * either unrolled or encoded before resolving the assignments and passing them to {@code
 * AssignmentFormulaHandler}.
 *
 * <p>By "unrolling", we mean replacing the quantified variable with discrete values it can take. As
 * this could make the formula too long or even infinitely long, the unrolling size is limited,
 * which does not preserve soundness.
 *
 * <p>By "encoding", we mean replacing the quantified variable with an SMT variable which is
 * quantified universally in the theory of quantifiers of the SMT solver. This is not supported by
 * all SMT solvers and may easily result in combinatorial explosion within the solver, but preserves
 * soundness.
 *
 * <p>Normal code should use {@link AssignmentHandler} for assignments instead which transforms
 * arbitrary assignments to simple assignments before using this handler.
 */
class AssignmentQuantifierHandler {

  /** Prefix of SMT-encoded variable name, followed by variable number. */
  private static final String ENCODED_VARIABLE_PREFIX = "__quantifier_";

  /**
   * Next encoded variable number. Is static so that quantified variables can be differentiated in
   * SMT solver formula even when the quantifier handler is constructed for each assignment
   * separately.
   */
  private static int NEXT_ENCODED_VARIABLE_NUMBER = 0;

  private final FormulaEncodingWithPointerAliasingOptions options;
  private final FormulaManagerView fmgr;
  private final BooleanFormulaManagerView bfmgr;

  private final CToFormulaConverterWithPointerAliasing conv;
  private final TypeHandlerWithPointerAliasing typeHandler;
  private final CFAEdge edge;
  private final String function;
  private final SSAMapBuilder ssa;
  private final PointerTargetSetBuilder pts;
  private final Constraints constraints;
  private final ErrorConditions errorConditions;
  private final MemoryRegionManager regionMgr;

  /** Assignment options, used for each assignment within the constructed handler. */
  private final AssignmentOptions assignmentOptions;

  /**
   * Resolved left-hand-side bases of assignments. For each assignment to be handled, the
   * left-hand-side base must be present as a key in this map.
   */
  private final Map<CRightHandSide, ArraySliceResolved> resolvedLhsBases;

  /**
   * Resolved left-hand-side bases of assignments. For each assignment to be handled, the base of
   * each deterministic right-hand-side part must be present as a key in this map.
   */
  private final Map<CRightHandSide, ArraySliceResolved> resolvedRhsBases;

  /** Machine model pointer-equivalent size type, retained here for conciseness. */
  private final CSimpleType sizeType;

  /**
   * Creates a new AssignmentQuantifierHandler.
   *
   * @param pConv The C to SMT formula converter.
   * @param pEdge The current edge of the CFA (for logging purposes).
   * @param pFunction The name of the current function.
   * @param pSsa The SSA map.
   * @param pPts The underlying set of pointer targets.
   * @param pConstraints Additional constraints.
   * @param pErrorConditions Additional error conditions.
   * @param pRegionMgr Memory region manager.
   * @param pAssignmentOptions Assignment options which will be used for each assignment within this
   *     handler.
   * @param pResolvedLhsBases Resolved left-hand-side bases.
   * @param pResolvedRhsBases Resolved right-hand-side bases.
   */
  AssignmentQuantifierHandler(
      CToFormulaConverterWithPointerAliasing pConv,
      CFAEdge pEdge,
      String pFunction,
      SSAMapBuilder pSsa,
      PointerTargetSetBuilder pPts,
      Constraints pConstraints,
      ErrorConditions pErrorConditions,
      MemoryRegionManager pRegionMgr,
      AssignmentOptions pAssignmentOptions,
      Map<CRightHandSide, ArraySliceResolved> pResolvedLhsBases,
      Map<CRightHandSide, ArraySliceResolved> pResolvedRhsBases) {
    conv = pConv;

    typeHandler = pConv.typeHandler;
    options = conv.options;
    fmgr = conv.fmgr;
    bfmgr = conv.bfmgr;

    edge = pEdge;
    function = pFunction;
    ssa = pSsa;
    pts = pPts;
    constraints = pConstraints;
    errorConditions = pErrorConditions;
    regionMgr = pRegionMgr;

    assignmentOptions = pAssignmentOptions;
    resolvedLhsBases = pResolvedLhsBases;
    resolvedRhsBases = pResolvedRhsBases;

    sizeType = conv.machineModel.getPointerEquivalentSimpleType();
  }

  /**
   * Performs simple slice assignments and returns the resulting Boolean formula.
   *
   * @param assignmentMultimap The multimap containing the simple slice assignments. Each LHS can
   *     have multiple partial RHS from which to assign. The full expression types of LHS and RHS
   *     cannot be array or composite types. The multimap should preserve order of addition for
   *     deterministic order of quantification.
   * @return The Boolean formula describing to assignments.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   * @throws InterruptedException If a shutdown was requested during assigning.
   */
  BooleanFormula assignSimpleSlices(
      final Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> assignmentMultimap)
      throws UnrecognizedCodeException, InterruptedException {

    // get a set of variables that we need to quantify (encode or unroll)
    // each variable can be present in more locations, so we use a set to remove duplicates
    // as we want to have deterministic order of quantification, we use a LinkedHashSet
    final LinkedHashSet<ArraySliceIndexVariable> variablesToQuantify = new LinkedHashSet<>();
    for (Entry<ArraySliceSpanLhs, Collection<ArraySliceSpanRhs>> entry :
        assignmentMultimap.asMap().entrySet()) {
      variablesToQuantify.addAll(entry.getKey().actual().getUnresolvedIndexVariables());
      for (ArraySliceSpanRhs rhs : entry.getValue()) {
        if (rhs.actual().isPresent()) {
          variablesToQuantify.addAll(rhs.actual().get().getUnresolvedIndexVariables());
        }
      }
    }

    // hand over to recursive quantification
    // initially, the condition for assignment to actually occur is true
    return quantifyAssignments(assignmentMultimap, variablesToQuantify, bfmgr.makeTrue());
  }

  /**
   * Recursively quantifies slice variables and performs the assignments when all variables are
   * quantified.
   *
   * <p>During each call with non-empty set of variables to quantify, one of the variables is
   * encoded or unrolled as selected. The requirements for the assignment to actually occur are
   * carried in the {@code condition} parameter: if it is not satisfied, the value is retained
   * instead. This is needed both for unrolling and encoding, as even with unrolling, the desired
   * variable range may not be statically known.
   *
   * @param assignmentMultimap The multimap containing the simple slice assignments.
   * @param variablesToQuantify Remaining variables that need to be quantified.
   * @param condition Boolean formula condition for the assignment to actually occur.
   * @return The Boolean formula describing the assignments.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   * @throws InterruptedException If a shutdown was requested during assigning.
   */
  private BooleanFormula quantifyAssignments(
      final Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> assignmentMultimap,
      final LinkedHashSet<ArraySliceIndexVariable> variablesToQuantify,
      final BooleanFormula condition)
      throws UnrecognizedCodeException, InterruptedException {

    if (variablesToQuantify.isEmpty()) {
      // all variables have been quantified, perform quantified assignment
      return assignSimpleSlicesWithResolvedIndexing(assignmentMultimap, condition);
    }

    // not all variables have been quantified, get the variable to quantify
    final ArraySliceIndexVariable variableToQuantify = variablesToQuantify.iterator().next();

    // remove the variable which will be quantified from the next variables to quantify
    final LinkedHashSet<ArraySliceIndexVariable> nextVariablesToQuantify =
        new LinkedHashSet<>(variablesToQuantify);
    nextVariablesToQuantify.remove(variableToQuantify);

    // get the variable slice size (the assignment is done for all i where 0 <= i < sliceSize)
    final CExpression sliceSize = variableToQuantify.getSize();
    // cast it to size type to get a proper formula
    final CExpression sliceSizeCastToSizeType =
        new CCastExpression(FileLocation.DUMMY, sizeType, sliceSize);
    // visit it to get the formula
    final CExpressionVisitorWithPointerAliasing indexSizeVisitor =
        new CExpressionVisitorWithPointerAliasing(
            conv, edge, function, ssa, constraints, errorConditions, pts, regionMgr);
    final Expression sliceSizeExpression = sliceSizeCastToSizeType.accept(indexSizeVisitor);
    final Formula sliceSizeFormula = indexSizeVisitor.asValueFormula(sliceSizeExpression, sizeType);

    // TODO: should we add fields to UF from index visitor?

    // decide whether to encode or unroll the quantifier
    // the functions are recursive and return the result of completed assignment
    if (shouldEncode()) {
      return encodeQuantifier(
          assignmentMultimap,
          nextVariablesToQuantify,
          condition,
          variableToQuantify,
          sliceSizeFormula);
    } else {
      return unrollQuantifier(
          assignmentMultimap,
          nextVariablesToQuantify,
          condition,
          variableToQuantify,
          sliceSizeFormula);
    }
  }

  /**
   * Decides whether a variable should be encoded or unrolled.
   *
   * @return True if we should encode, false if we should unroll the variable.
   */
  private boolean shouldEncode() {
    // encode if the quantifiers are selected in global options or forced in assignment options
    // unroll otherwise
    // note that currently, all variables within the same assignment call behave the same,
    // but this behavior can be changed in the future if necessary: the handling is ready for it
    return options.useQuantifiersOnArrays() || assignmentOptions.forceQuantifiers();
  }

  /**
   * Encodes the quantifier for the given slice variable in the SMT solver theory of quantifiers and
   * calls {@link #quantifyAssignments(Multimap, LinkedHashSet, BooleanFormula)} recursively.
   *
   * @param assignmentMultimap The multimap containing the simple slice assignments.
   * @param nextVariablesToQuantify Remaining variables that need to be quantified, without the one
   *     to currently encode.
   * @param condition Boolean formula condition for the assignment to actually occur.
   * @param variableToEncode The variable to be encoded here.
   * @param sliceSizeFormula The formula for slice size. The assignment should occur iff {@code 0 <=
   *     i < sliceSizeFormula} where {@code i} is the encoded variable.
   * @return The Boolean formula describing the assignments.
   */
  private BooleanFormula encodeQuantifier(
      Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> assignmentMultimap,
      LinkedHashSet<ArraySliceIndexVariable> nextVariablesToQuantify,
      BooleanFormula condition,
      ArraySliceIndexVariable variableToEncode,
      Formula sliceSizeFormula)
      throws UnrecognizedCodeException, InterruptedException {

    // the quantified variable should be of size type
    final FormulaType<?> sizeFormulaType = conv.getFormulaTypeFromCType(sizeType);
    final Formula zeroFormula = conv.fmgr.makeNumber(sizeFormulaType, 0);
    final boolean sizeTypeIsSigned = sizeType.getCanonicalType().isSigned();

    // create encoded quantified variable
    final Formula encodedVariable =
        fmgr.makeVariableWithoutSSAIndex(
            sizeFormulaType, ENCODED_VARIABLE_PREFIX + NEXT_ENCODED_VARIABLE_NUMBER++);

    // resolve in assignments
    // for every (LHS or RHS) slice, we replace it with a slice that has unresolved indexing
    // by variableToUnroll replaced by resolved indexing by indexFormula
    final Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> nextAssignmentMultimap =
        mapAssignmentSlices(
            assignmentMultimap, slice -> slice.resolveVariable(variableToEncode, encodedVariable));

    // create the condition for quantifier
    // the quantified variable condition holds when 0 <= index < size
    // note that the size type may be signed, so we must do the less-or-equal constraint
    final BooleanFormula nextCondition =
        bfmgr.and(
            condition,
            fmgr.makeLessOrEqual(zeroFormula, encodedVariable, sizeTypeIsSigned),
            fmgr.makeLessThan(encodedVariable, sliceSizeFormula, sizeTypeIsSigned));

    // recurse and get the assignment result
    final BooleanFormula assignmentResult =
        quantifyAssignments(nextAssignmentMultimap, nextVariablesToQuantify, nextCondition);

    // add quantifier around the recursion result
    return fmgr.getQuantifiedFormulaManager().forall(encodedVariable, assignmentResult);
  }

  /**
   * Unrolls the quantifier for the given slice variable in the and calls {@link
   * #quantifyAssignments(Multimap, LinkedHashSet, BooleanFormula)} recursively.
   *
   * <p>This is unsound if the length of unrolling is not sufficient. If UFs are used, it also may
   * be unsound due to other assignments within the same aliased location not being retained.
   *
   * @param assignmentMultimap The multimap containing the simple slice assignments.
   * @param nextVariablesToQuantify Remaining variables that need to be quantified, without the one
   *     to currently encode.
   * @param condition Boolean formula condition for the assignment to actually occur.
   * @param variableToUnroll The variable to be unrolled here.
   * @param sliceSizeFormula The formula for slice size. The assignment should occur iff {@code 0 <=
   *     i < sliceSizeFormula} where {@code i} is the unrolled variable.
   * @return The Boolean formula describing the assignments.
   */
  private BooleanFormula unrollQuantifier(
      Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> assignmentMultimap,
      LinkedHashSet<ArraySliceIndexVariable> nextVariablesToQuantify,
      BooleanFormula condition,
      ArraySliceIndexVariable variableToUnroll,
      Formula sliceSizeFormula)
      throws UnrecognizedCodeException, InterruptedException {

    // the unrolled index should be of size type
    final FormulaType<?> sizeFormulaType = conv.getFormulaTypeFromCType(sizeType);
    final Formula zeroFormula = conv.fmgr.makeNumber(sizeFormulaType, 0);
    final boolean sizeTypeSigned = sizeType.getCanonicalType().isSigned();

    // limit the unrolling size to a reasonable number by default
    long unrollingSize = options.defaultArrayLength();

    // if the expression is a literal, we can get the exact slice size
    final CExpression sliceSize = variableToUnroll.getSize();
    if (sliceSize instanceof CIntegerLiteralExpression literalSliceSize) {
      final long exactSliceSize =
          ((CIntegerLiteralExpression) sliceSize).getValue().longValueExact();
      // decide whether the literal size is not longer than reasonable for instances where
      // we know the size exactly; note that the reasonable sizes may be different depending on
      // whether the slice size is a literal or not
      if (options.maxArrayLength() >= 0 && exactSliceSize > options.maxArrayLength()) {
        // unreasonable exact slice size, limit and warn
        unrollingSize = options.maxArrayLength();
        // warn just once for all literal unrollings to avoid polluting the output
        conv.logger.logfOnce(
            Level.WARNING,
            "Limiting unrolling of literal-length slice assignment to %s, soundness may be lost",
            options.maxArrayLength());
      } else {
        // reasonable exact slice size, soundness is guaranteed
        unrollingSize = exactSliceSize;
      }
    } else {
      // non-literal slice size expression, always potentially unsound
      // warn just once for all non-literal unrollings to avoid polluting the output
      conv.logger.logfOnce(
          Level.WARNING,
          "Limiting unrolling of non-literal-length slice assignment to %s, soundness may be lost",
          options.maxArrayLength());
    }

    // the result will be a conjunction of unrolled assignment results
    BooleanFormula result = bfmgr.makeTrue();

    // for all 0 <= i < unrollingSize, perform assignments with the variable formula set to i
    for (long i = 0; i < unrollingSize; ++i) {
      // construct the index formula
      final Formula indexFormula = conv.fmgr.makeNumber(sizeFormulaType, i);

      // perform the unrolled assignments conditionally
      // the variable condition holds when 0 <= i < size
      final BooleanFormula nextCondition =
          bfmgr.and(
              condition,
              fmgr.makeLessOrEqual(zeroFormula, indexFormula, sizeTypeSigned),
              fmgr.makeLessThan(indexFormula, sliceSizeFormula, sizeTypeSigned));

      // resolve the quantifier in assignments
      // for every (LHS or RHS) slice, we replace it with a slice that has unresolved indexing
      // by variableToUnroll replaced by resolved indexing by indexFormula
      final Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> nextAssignmentMultimap =
          mapAssignmentSlices(
              assignmentMultimap, slice -> slice.resolveVariable(variableToUnroll, indexFormula));

      // quantify recursively
      final BooleanFormula recursionResult =
          quantifyAssignments(nextAssignmentMultimap, nextVariablesToQuantify, nextCondition);

      // result is conjunction of unrolled assignment results
      result = bfmgr.and(result, recursionResult);
    }

    return result;
  }

  /**
   * Apply a given function to every slice in assignment multimap.
   *
   * <p>Used to replace a quantified variable in every slice with its resolved formula.
   *
   * @param assignmentMultimap Assignment multimap.
   * @param sliceMappingFunction A function to apply to every {@code ArraySliceExpression} in the
   *     multimap.
   * @return A new multimap with the function applied, with no other changes. Preserves ordering of
   *     {@code assignmentMultimap}.
   */
  private Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> mapAssignmentSlices(
      final Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> assignmentMultimap,
      final Function<ArraySliceExpression, ArraySliceExpression> sliceMappingFunction) {

    // LinkedHashMultimap to preserve ordering
    final Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> result = LinkedHashMultimap.create();

    // iterate over all LHS
    for (Entry<ArraySliceSpanLhs, Collection<ArraySliceSpanRhs>> assignment :
        assignmentMultimap.asMap().entrySet()) {
      // apply the function to the LHS slice
      final ArraySliceExpression mappedLhsSlice =
          sliceMappingFunction.apply(assignment.getKey().actual());
      // construct the whole LHS
      final ArraySliceSpanLhs mappedLhs =
          new ArraySliceSpanLhs(mappedLhsSlice, assignment.getKey().targetType());

      // iterate over all RHS
      for (ArraySliceSpanRhs rhs : assignment.getValue()) {
        // apply the function to the RHS slice if it exists
        // (if it does not, it is taken as nondet)
        final Optional<ArraySliceExpression> resolvedRhsSlice =
            rhs.actual().map(rhsSlice -> sliceMappingFunction.apply(rhsSlice));
        // construct the whole RHS and put the result into the new multimap
        final ArraySliceSpanRhs resolvedRhs = new ArraySliceSpanRhs(rhs.span(), resolvedRhsSlice);
        result.put(mappedLhs, resolvedRhs);
      }
    }
    return result;
  }

  /**
   * Performs simple slice assignments and returns the resulting Boolean formula. All indexing
   * modifiers in the assignments must be already resolved.
   *
   * @param assignmentMultimap The multimap containing the simple slice assignments. All indexing
   *     modifiers must be resolved.
   * @param condition Boolean formula condition for the assignment to actually occur.
   * @return The Boolean formula describing the assignments.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   * @throws InterruptedException If a shutdown was requested during assigning.
   */
  private BooleanFormula assignSimpleSlicesWithResolvedIndexing(
      final Multimap<ArraySliceSpanLhs, ArraySliceSpanRhs> assignmentMultimap,
      final BooleanFormula condition)
      throws UnrecognizedCodeException, InterruptedException {

    // construct a formula handler
    final AssignmentFormulaHandler assignmentFormulaHandler =
        new AssignmentFormulaHandler(conv, edge, ssa, pts, constraints, errorConditions, regionMgr);

    // the result is a conjunction of assignments
    BooleanFormula result = bfmgr.makeTrue();

    // for each assignment, perform it using the formula handler and conjunct the result
    for (Entry<ArraySliceSpanLhs, Collection<ArraySliceSpanRhs>> assignment :
        assignmentMultimap.asMap().entrySet()) {

      final ArraySliceExpression lhs = assignment.getKey().actual();
      final CType targetType = assignment.getKey().targetType();

      // resolve the LHS by getting the resolved base and resolving modifiers over it
      final ArraySliceResolved lhsResolvedBase = resolvedLhsBases.get(lhs.getBase());
      assert (lhsResolvedBase != null);
      final ArraySliceResolved lhsResolved =
          lhs.resolveModifiers(lhsResolvedBase, conv, ssa, errorConditions, regionMgr);

      // skip assignment if LHS is nondet
      if (lhsResolved.expression().isNondetValue()) {
        // should only happen when we cannot assign to aliased bitfields
        // TODO: implement aliased bitfields
        continue;
      }

      final List<ArraySliceSpanResolved> rhsResolvedList = new ArrayList<>();

      // resolve each RHS and collect them into a list
      for (ArraySliceSpanRhs rhs : assignment.getValue()) {

        // make nondet RHS into nondet resolved
        if (rhs.actual().isEmpty()) {
          rhsResolvedList.add(new ArraySliceSpanResolved(rhs.span(), Optional.empty()));
          continue;
        }

        // resolve the RHS by getting the resolved base and resolving modifiers over it
        final ArraySliceExpression rhsSlice = rhs.actual().get();
        final ArraySliceResolved rhsResolvedBase = resolvedRhsBases.get(rhsSlice.getBase());
        assert (rhsResolvedBase != null);
        ArraySliceResolved rhsResolved =
            rhsSlice.resolveModifiers(rhsResolvedBase, conv, ssa, errorConditions, regionMgr);

        // after resolving rhs, the rhs resolved type may be array even if we want to do
        // pointer assignment, signified by pointer target type
        // make rhs resolved target type into pointer in that case
        if (targetType instanceof CPointerType) {
          rhsResolved =
              new ArraySliceResolved(
                  rhsResolved.expression(), CTypes.adjustFunctionOrArrayType(rhsResolved.type()));
        }
        // add resolved RHS to list
        rhsResolvedList.add(new ArraySliceSpanResolved(rhs.span(), Optional.of(rhsResolved)));
      }

      // compute pointer-target set pattern if necessary for UFs finishing
      // UFs must be finished only if all three of the following conditions are met:
      // 1. UF heap is used
      // 2. lhs is in aliased location (unaliased location is assigned as a whole)
      // 3. using old SSA indices is not selected
      final PointerTargetPattern pattern =
          !options.useArraysForHeap()
                  && lhsResolved.expression().isAliasedLocation()
                  && !assignmentOptions.useOldSSAIndicesIfAliased()
              ? PointerTargetPattern.forLeftHandSide(
                  (CLeftHandSide) lhs.getDummyResolvedExpression(sizeType), typeHandler, edge, pts)
              : null;

      // make the actual assignment
      result =
          bfmgr.and(
              result,
              assignmentFormulaHandler.assignResolvedSlice(
                  lhsResolved,
                  targetType,
                  rhsResolvedList,
                  assignmentOptions,
                  condition,
                  false,
                  pattern));
    }

    return result;
  }

}
