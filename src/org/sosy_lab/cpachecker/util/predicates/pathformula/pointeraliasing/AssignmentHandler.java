// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CLeftHandSide;
import org.sosy_lab.cpachecker.cfa.ast.c.CRightHandSide;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.types.c.CArrayType;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType.CCompositeTypeMemberDeclaration;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.exceptions.UnrecognizedCodeException;
import org.sosy_lab.cpachecker.util.predicates.pathformula.ErrorConditions;
import org.sosy_lab.cpachecker.util.predicates.pathformula.SSAMap.SSAMapBuilder;
import org.sosy_lab.cpachecker.util.predicates.pathformula.ctoformula.Constraints;
import org.sosy_lab.cpachecker.util.predicates.pathformula.ctoformula.IsRelevantWithHavocAbstractionVisitor;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.ArraySliceExpression.ArraySliceIndexVariable;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.ArraySliceExpression.ArraySliceResolved;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.ArraySliceExpression.ArraySliceSplitExpression;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.ArraySliceExpression.ArraySliceTail;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.Expression.Location;
import org.sosy_lab.cpachecker.util.predicates.smt.ArrayFormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.BooleanFormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.java_smt.api.ArrayFormula;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.Formula;
import org.sosy_lab.java_smt.api.FormulaType;

/** Implements a handler for assignments. */
class AssignmentHandler {

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

  private final AssignmentQuantifierHandler assignmentQuantifierHandler;

  /**
   * Creates a new AssignmentHandler.
   *
   * @param pConv The C to SMT formula converter.
   * @param pEdge The current edge of the CFA (for logging purposes).
   * @param pFunction The name of the current function.
   * @param pSsa The SSA map.
   * @param pPts The underlying set of pointer targets.
   * @param pConstraints Additional constraints.
   * @param pErrorConditions Additional error conditions.
   */
  AssignmentHandler(
      CToFormulaConverterWithPointerAliasing pConv,
      CFAEdge pEdge,
      String pFunction,
      SSAMapBuilder pSsa,
      PointerTargetSetBuilder pPts,
      Constraints pConstraints,
      ErrorConditions pErrorConditions,
      MemoryRegionManager pRegionMgr) {
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

    assignmentQuantifierHandler =
        new AssignmentQuantifierHandler(
            pConv, pEdge, pFunction, pSsa, pPts, pConstraints, pErrorConditions, pRegionMgr);
  }

  /**
   * Creates a formula to handle assignments.
   *
   * @param lhs The left hand side of an assignment.
   * @param lhsForChecking The left hand side of an assignment to check.
   * @param rhs Either {@code null} or the right hand side of the assignment.
   * @param useOldSSAIndicesIfAliased A flag indicating whether we can use old SSA indices for
   *     aliased locations (because the location was not used before)
   * @return A formula for the assignment.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   * @throws InterruptedException If the execution was interrupted.
   */
  BooleanFormula handleAssignment(
      final CLeftHandSide lhs,
      final CLeftHandSide lhsForChecking,
      final CType lhsType,
      final @Nullable CRightHandSide rhs,
      final boolean useOldSSAIndicesIfAliased)
      throws UnrecognizedCodeException, InterruptedException {
    return handleAssignment(lhs, lhsForChecking, lhsType, rhs, useOldSSAIndicesIfAliased, false);
  }

  /**
   * Creates a formula to handle assignments.
   *
   * @param lhs The left hand side of an assignment.
   * @param lhsForChecking The left hand side of an assignment to check.
   * @param rhs Either {@code null} or the right hand side of the assignment.
   * @param useOldSSAIndicesIfAliased A flag indicating whether we can use old SSA indices for
   *     aliased locations (because the location was not used before)
   * @param reinterpretInsteadOfCasting A flag indicating whether we should reinterpret the
   *     right-hand side type, preserving the bit-vector representation, to the left-hand side type
   *     instead of casting it according to C rules
   * @return A formula for the assignment.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   * @throws InterruptedException If the execution was interrupted.
   */
  BooleanFormula handleAssignment(
      final CLeftHandSide lhs,
      final CLeftHandSide lhsForChecking,
      final CType lhsType,
      final @Nullable CRightHandSide rhs,
      final boolean useOldSSAIndicesIfAliased,
      final boolean reinterpretInsteadOfCasting)
      throws UnrecognizedCodeException, InterruptedException {
    // TODO: remake the parameters
    if (!conv.isRelevantLeftHandSide(lhsForChecking)) {
      // Optimization for unused variables and fields
      return conv.bfmgr.makeTrue();
    }

    final ArraySliceExpression lhsSlice = new ArraySliceExpression(lhs);
    final ArraySliceRhs rhsSlice;
    if (rhs != null) {
      if (rhs instanceof CExpression rhsExpression) {
      rhsSlice = new ArraySliceExpressionRhs(new ArraySliceExpression(rhsExpression));
      } else if (rhs instanceof CFunctionCallExpression rhsCall) {
        rhsSlice = new ArraySliceCallRhs(rhsCall);
      } else {
        assert(false);
        rhsSlice = null;
      }
    } else {
      rhsSlice = new ArraySliceNondetRhs();
    }

    ArraySliceAssignment assignment = new ArraySliceAssignment(lhsSlice, rhsSlice);

    AssignmentOptions assignmentOptions =
        new AssignmentOptions(
            useOldSSAIndicesIfAliased,
            reinterpretInsteadOfCasting
                ? AssignmentConversionType.REINTERPRET
                : AssignmentConversionType.CAST,
            false,
            !lhsType.equals(typeHandler.getSimplifiedType(lhs)));


    return handleSliceAssignment(assignment, assignmentOptions);
  }

  record ArraySlicePartSpan(long lhsBitOffset, long rhsBitOffset, long bitSize) {}

  sealed interface ArraySliceRhs
      permits ArraySliceExpressionRhs, ArraySliceCallRhs, ArraySliceNondetRhs {
    CType getType(CType targetType, CType sizeType);
  }

  record ArraySliceExpressionRhs(ArraySliceExpression expression) implements ArraySliceRhs {
    ArraySliceExpressionRhs(ArraySliceExpression expression) {
      checkNotNull(expression);
      this.expression = expression;
    }

    @Override
    public CType getType(CType targetType, CType sizeType) {
      // TODO Auto-generated method stub
      return expression.getResolvedExpressionType(sizeType);
    }
  }

  record ArraySliceCallRhs(CFunctionCallExpression call) implements ArraySliceRhs {
    ArraySliceCallRhs(CFunctionCallExpression call) {
      checkNotNull(call);
      this.call = call;
    }

    @Override
    public CType getType(CType targetType, CType sizeType) {
      return call.getExpressionType();
    }

  }

  record ArraySliceNondetRhs() implements ArraySliceRhs {
    @Override
    public CType getType(CType targetType, CType sizeType) {
      return targetType;
    }
  }

  record ArraySliceSpanRhs(ArraySlicePartSpan span, ArraySliceRhs actual) {
    ArraySliceSpanRhs(ArraySlicePartSpan span, ArraySliceRhs actual) {
      checkNotNull(span);
      checkNotNull(actual);
      this.span = span;
      this.actual = actual;
    }
  }

  record ArraySliceSpanLhs(ArraySliceExpression actual, CType targetType) {
    ArraySliceSpanLhs(ArraySliceExpression actual, CType targetType) {
      checkNotNull(actual);
      checkNotNull(targetType);
      this.actual = actual;
      this.targetType = targetType;
    }
  }

  record ArraySliceSpanAssignment(ArraySliceSpanLhs lhs, ArraySliceSpanRhs rhs) {
    ArraySliceSpanAssignment(ArraySliceSpanLhs lhs, ArraySliceSpanRhs rhs) {
      checkNotNull(lhs);
      checkNotNull(rhs);
      this.lhs = lhs;
      this.rhs = rhs;
    }
  }

  record ArraySliceSpanResolved(ArraySlicePartSpan span, ArraySliceResolved actual) {
    ArraySliceSpanResolved(ArraySlicePartSpan span, ArraySliceResolved actual) {
      checkNotNull(span);
      checkNotNull(actual);
      this.span = span;
      this.actual = actual;
    }
  }

  record ArraySliceAssignment(ArraySliceExpression lhs, ArraySliceRhs rhs) {
    ArraySliceAssignment(ArraySliceExpression lhs, ArraySliceRhs rhs) {
      checkNotNull(lhs);
      checkNotNull(rhs);
      this.lhs = lhs;
      this.rhs = rhs;
    }
  }

  enum AssignmentConversionType {
    CAST,
    REINTERPRET
  }

  record AssignmentOptions(
      boolean useOldSSAIndicesIfAliased,
      AssignmentConversionType conversionType,
      boolean forceQuantifiers,
      boolean forcePointerAssignment) {
    AssignmentOptions(
        boolean useOldSSAIndicesIfAliased,
        AssignmentConversionType conversionType,
        boolean forceQuantifiers,
        boolean forcePointerAssignment) {
      checkNotNull(conversionType);
      this.useOldSSAIndicesIfAliased = useOldSSAIndicesIfAliased;
      this.conversionType = conversionType;
      this.forceQuantifiers = forceQuantifiers;
      this.forcePointerAssignment = forcePointerAssignment;
    }
  }

  BooleanFormula handleSliceAssignment(
      ArraySliceAssignment assignment, final AssignmentOptions assignmentOptions)
      throws UnrecognizedCodeException, InterruptedException {
    return handleSliceAssignments(ImmutableList.of(assignment), assignmentOptions);
  }

  BooleanFormula handleSliceAssignments(
      List<ArraySliceAssignment> assignments, final AssignmentOptions assignmentOptions)
      throws UnrecognizedCodeException, InterruptedException {

    CSimpleType sizeType = conv.machineModel.getPointerEquivalentSimpleType();

    // apply Havoc abstraction: if Havoc abstraction is turned on
    // and rhs is not relevant, make it nondeterministic
    List<ArraySliceAssignment> appliedHavocAssignments = new ArrayList<>();
    if (conv.options.useHavocAbstraction()) {
      for (ArraySliceAssignment assignment : assignments) {
        ArraySliceAssignment appliedHavocAssignment = assignment;
        // function call rhs are always relevant
        // nondet rhs can be always retained
        // only expression rhs have to be tested
        if (assignment.rhs instanceof ArraySliceExpressionRhs expressionRhs) {
          // the Havoc relevant visitor does not care about subscripts and fields,
          // we can just test for relevancy of the base
          if (expressionRhs
              .expression
              .getBaseExpression()
              .accept(new IsRelevantWithHavocAbstractionVisitor(conv))) {
            // make nondet
            appliedHavocAssignment =
                new ArraySliceAssignment(assignment.lhs, new ArraySliceNondetRhs());
          }
        }
        appliedHavocAssignments.add(appliedHavocAssignment);
      }
    } else {
      appliedHavocAssignments.addAll(assignments);
    }

    // generate simple slice assignments to resolve assignments to structures and arrays
    List<ArraySliceSpanAssignment> simpleAssignments = new ArrayList<>();

    for (ArraySliceAssignment assignment : assignments) {

      CType lhsType = typeHandler.simplifyType(assignment.lhs.getResolvedExpressionType(sizeType));

      final CType rhsNonsimplifiedType;
        if (assignment.rhs instanceof ArraySliceExpressionRhs expressionRhs) {
          rhsNonsimplifiedType = expressionRhs.expression.getResolvedExpressionType(sizeType);
        } else if (assignment.rhs instanceof ArraySliceCallRhs callRhs) {
          rhsNonsimplifiedType = callRhs.call.getExpressionType().getCanonicalType();
        } else if (assignment.rhs instanceof ArraySliceNondetRhs nondetRhs) {
          // get lhs type
          rhsNonsimplifiedType = assignment.lhs.getResolvedExpressionType(sizeType);
        } else {
          assert (false);
          rhsNonsimplifiedType = null;
        }
      final CType rhsType = typeHandler.simplifyType(rhsNonsimplifiedType);

      // to initialize the span size, we need to know the type after potential casting
      // this is usually the type of lhs, but if pointer assignment is being forced,
      // we need to take it from rhs
      final CType targetType;
      if (assignmentOptions.forcePointerAssignment) {
        targetType = rhsType;
      } else {
        targetType = lhsType;
      }

      long targetBitSize = typeHandler.getBitSizeof(targetType);

      ArraySliceSpanAssignment spanAssignment =
          new ArraySliceSpanAssignment(
              new ArraySliceSpanLhs(assignment.lhs, targetType),
              new ArraySliceSpanRhs(new ArraySlicePartSpan(0, 0, targetBitSize), assignment.rhs));
      if (assignmentOptions.forcePointerAssignment) {
        // actual assignment type should be pointer, which is already simple
        simpleAssignments.add(spanAssignment);
      } else {
        // convert to progenitor
        ArraySliceSpanAssignment progenitorAssignment =
            convertSliceAssignmentLhsToProgenitor(spanAssignment);

        generateSimpleSliceAssignments(progenitorAssignment, simpleAssignments);
      }
    }
    // hand over
    return assignmentQuantifierHandler.handleSimpleSliceAssignments(simpleAssignments, assignmentOptions);
  }

  private ArraySliceSpanAssignment convertSliceAssignmentLhsToProgenitor(
      ArraySliceSpanAssignment assignment) {

    CSimpleType sizeType = conv.machineModel.getPointerEquivalentSimpleType();

    // split the lhs into a base part followed by field accesses
    // e.g. with (*x).a.b.c.d, split into (*x) and .a.b.c.d
    // the base part is the progenitor from which we will be assigning to span

    final ArraySliceSplitExpression splitLhs = assignment.lhs.actual.getSplit();
    final ArraySliceExpression progenitor = splitLhs.head();
    final CType progenitorType = progenitor.getResolvedExpressionType(sizeType);
    final ArraySliceTail tail = splitLhs.tail();

    // compute the full offset from progenitor

    CType parentType = progenitorType;

    long bitOffsetFromProgenitor = 0;

    for (CCompositeTypeMemberDeclaration currentFieldAccess : tail.list()) {
      // field access, parent must be composite
      CCompositeType parentCompositeType = (CCompositeType) parentType;

      // add current field access to bit offset from progenitor
      bitOffsetFromProgenitor += typeHandler.getBitOffset(parentCompositeType, currentFieldAccess);

      parentType = typeHandler.getSimplifiedType(currentFieldAccess);
    }

    ArraySlicePartSpan originalSpan = assignment.rhs.span;
    ArraySlicePartSpan spanFromProgenitor = new ArraySlicePartSpan(
        bitOffsetFromProgenitor + originalSpan.lhsBitOffset,
        originalSpan.rhsBitOffset,
        originalSpan.bitSize
        );

    // now construct the new assignment with lhs being the progenitor and span modified accordingly
    // rhs does not change, so target type does not change as well
    return new ArraySliceSpanAssignment(
        new ArraySliceSpanLhs(progenitor, assignment.lhs.targetType),
        new ArraySliceSpanRhs(spanFromProgenitor, assignment.rhs.actual));
  }

  private void generateSimpleSliceAssignments(
      ArraySliceSpanAssignment assignment, List<ArraySliceSpanAssignment> simpleAssignments) {

    CSimpleType sizeType = conv.machineModel.getPointerEquivalentSimpleType();

    CType lhsType =
        typeHandler.simplifyType(assignment.lhs.actual.getResolvedExpressionType(sizeType));


    boolean rhsIsExpression = assignment.rhs.actual instanceof ArraySliceExpressionRhs;
    boolean rhsIsNondet = assignment.rhs.actual instanceof ArraySliceNondetRhs;

    if (lhsType instanceof CCompositeType lhsCompositeType) {

      ArraySlicePartSpan originalSpan = assignment.rhs.span;

        for (CCompositeTypeMemberDeclaration lhsMember : lhsCompositeType.getMembers()) {
          long lhsMemberBitOffset = typeHandler.getBitOffset(lhsCompositeType, lhsMember);
          long lhsMemberBitSize = typeHandler.getBitSizeof(lhsMember.getType());
        final ArraySliceExpression lhsMemberSlice =
            assignment.lhs.actual.withFieldAccess(lhsMember);

          Range<Long> lhsOriginalRange = Range.closedOpen(
              originalSpan.lhsBitOffset,
              originalSpan.lhsBitOffset + originalSpan.bitSize
              );
          Range<Long> lhsMemberRange = Range.closedOpen(lhsMemberBitOffset, lhsMemberBitOffset + lhsMemberBitSize);
          if (!lhsOriginalRange.isConnected(lhsMemberRange)) {
            // the span does not cover this member
            continue;
          }

          Range<Long> lhsIntersectionRange = lhsOriginalRange.intersection(lhsMemberRange);
          if (lhsIntersectionRange.isEmpty()) {
            // the span does not cover this member
            continue;
          }

          // create the assignment to member which is referenced to the member
          long intersectionMemberReferencedLhsBitOffset = lhsIntersectionRange.lowerEndpoint() - lhsMemberBitOffset;
          long intersectionBitSize = lhsIntersectionRange.upperEndpoint() - lhsIntersectionRange.lowerEndpoint();

          ArraySliceSpanAssignment memberAssignment;

        if (originalSpan.lhsBitOffset == originalSpan.rhsBitOffset
            && ((rhsIsExpression
                    && assignment
                        .rhs
                        .actual
                        .getType(assignment.lhs.targetType, sizeType)
                        .equals(lhsType))
                || rhsIsNondet)) {

          // types and offsets are equal, go into rhs as well

          // the offsets will remain the same for lhs and rhs
          ArraySlicePartSpan memberSpan =
              new ArraySlicePartSpan(
                  intersectionMemberReferencedLhsBitOffset,
                  intersectionMemberReferencedLhsBitOffset,
                  intersectionBitSize);

          final ArraySliceRhs rhsMemberRhs;
          if (rhsIsExpression) {
            rhsMemberRhs =
                new ArraySliceExpressionRhs(
                    ((ArraySliceExpressionRhs) assignment.rhs.actual)
                        .expression.withFieldAccess(lhsMember));
          } else if (rhsIsNondet) {
            rhsMemberRhs = new ArraySliceNondetRhs();
          } else {
            rhsMemberRhs = null;
            assert (false);
          }

          ArraySliceSpanRhs memberRhs = new ArraySliceSpanRhs(memberSpan, rhsMemberRhs);

          // target type is now member type
          CType memberTargetType = typeHandler.getSimplifiedType(lhsMember);

          memberAssignment =
              new ArraySliceSpanAssignment(
                  new ArraySliceSpanLhs(lhsMemberSlice, memberTargetType), memberRhs);

          } else {
          // types or offsets are not equal, do not go into rhs, just get the right spans

          // the rhs offset is still referenced to rhs which does not change, but the intersection
          // may start after original, so add intersection lhs bit offset and subtract original
          // lhs bit offset
          long intersectionRhsBitOffset =
              originalSpan.rhsBitOffset
                  + lhsIntersectionRange.lowerEndpoint()
                  - lhsOriginalRange.lowerEndpoint();

          ArraySlicePartSpan memberSpan =
              new ArraySlicePartSpan(
                  intersectionMemberReferencedLhsBitOffset,
                  intersectionRhsBitOffset,
                  intersectionBitSize);
          ArraySliceSpanRhs memberRhs = new ArraySliceSpanRhs(memberSpan, assignment.rhs.actual);

          // target type does not change
          memberAssignment =
              new ArraySliceSpanAssignment(
                  new ArraySliceSpanLhs(lhsMemberSlice, assignment.lhs.targetType), memberRhs);
          }
          generateSimpleSliceAssignments(memberAssignment, simpleAssignments);
      }
    } else if (lhsType instanceof CArrayType lhsArrayType) {
      @Nullable CExpression lhsArrayLength = lhsArrayType.getLength();
      if (lhsArrayLength == null) {
        // TODO: add flexible array member assignment, tracking the length from malloc
        conv.logger.logfOnce(
            Level.WARNING,
            "%s: Ignoring slice assignment to flexible array member %s as they are not well-supported",
            edge.getFileLocation(),
            lhsArrayType);
        return;
      }

      if (rhsIsExpression) {
        CType rhsType = assignment.rhs.actual.getType(assignment.lhs.targetType, sizeType);
        if (!lhsType.equals(rhsType)) {
          // we currently do not assign to array types from different types as that would
          // require spans to support quantification, which would be problematic
          // it should be only required for cases of unions containing arrays
          conv.logger.logfOnce(
              Level.WARNING,
              "%s: Ignoring assignment to array type %s from different type %s",
              edge.getFileLocation(),
              lhsArrayType,
              rhsType);
          return;
        }
      }

      ArraySlicePartSpan originalSpan = assignment.rhs.span;

      if (originalSpan.lhsBitOffset != 0
          || originalSpan.rhsBitOffset != 0
          || originalSpan.bitSize != typeHandler.getBitSizeof(lhsArrayType)) {
        // we currently do not assign to array types from different types as that would ideally
        // require spans
        // to support quantification, which would be problematic
        // it should be only required for cases of unions containing arrays
        conv.logger.logfOnce(
            Level.WARNING,
            "%s: Ignoring assignment to array type %s with non-full span",
            edge.getFileLocation(),
            lhsArrayType);
        return;
      }

      // add an assignment of every element of array using a quantified variable
      ArraySliceIndexVariable indexVariable = new ArraySliceIndexVariable(lhsArrayType.getLength());
      ArraySliceExpression elementLhs = assignment.lhs.actual.withIndex(indexVariable);
      final ArraySliceRhs elementRhs;
      if (rhsIsExpression) {
        elementRhs =
            new ArraySliceExpressionRhs(
                ((ArraySliceExpressionRhs) assignment.rhs.actual)
                    .expression.withIndex(indexVariable));
      } else if (rhsIsNondet) {
        elementRhs = new ArraySliceNondetRhs();
      } else {
        // TODO: remove separate handling of calls
        conv.logger.logfOnce(
            Level.WARNING,
            "%s: Ignoring assignment to array type %s from call",
            edge.getFileLocation(),
            lhsArrayType);
        return;
      }
      CType elementType = typeHandler.simplifyType(lhsArrayType.getType());
      // full span
      ArraySlicePartSpan elementSpan =
          new ArraySlicePartSpan(0, 0, typeHandler.getBitSizeof(elementType));
      ArraySliceSpanRhs elementSpanRhs = new ArraySliceSpanRhs(elementSpan, elementRhs);
      // target type is now element type
      ArraySliceSpanAssignment elementAssignment =
          new ArraySliceSpanAssignment(
              new ArraySliceSpanLhs(elementLhs, elementType), elementSpanRhs);
      generateSimpleSliceAssignments(elementAssignment, simpleAssignments);

    } else {
      // already simple, just add the assignment to simple assignments
      simpleAssignments.add(assignment);
    }
  }

  BooleanFormula handleAssignment(
      final CLeftHandSide lhs,
      final CLeftHandSide lhsForChecking,
      final @Nullable CRightHandSide rhs,
      final boolean useOldSSAIndicesIfAliased)
      throws UnrecognizedCodeException, InterruptedException {
    return handleAssignment(
        lhs, lhsForChecking, typeHandler.getSimplifiedType(lhs), rhs, useOldSSAIndicesIfAliased);
  }

  BooleanFormula handleAssignment(
      final CLeftHandSide lhs,
      final CLeftHandSide lhsForChecking,
      final @Nullable CRightHandSide rhs,
      final boolean useOldSSAIndicesIfAliased,
      final boolean reinterpretInsteadOfCasting)
      throws UnrecognizedCodeException, InterruptedException {
    return handleAssignment(
        lhs,
        lhsForChecking,
        typeHandler.getSimplifiedType(lhs),
        rhs,
        useOldSSAIndicesIfAliased,
        reinterpretInsteadOfCasting);
  }

  /**
   * Handles initialization assignments.
   *
   * @param variable The declared variable.
   * @param declarationType The type of the declared variable.
   * @param assignments A list of assignment statements.
   * @return A boolean formula for the assignment.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   * @throws InterruptedException It the execution was interrupted.
   */
  BooleanFormula handleInitializationAssignments(
      final CIdExpression variable,
      final CType declarationType,
      final List<CExpressionAssignmentStatement> assignments)
      throws UnrecognizedCodeException, InterruptedException {
    if (options.useQuantifiersOnArrays()
        && (declarationType instanceof CArrayType)
        && !assignments.isEmpty()) {
      return handleInitializationAssignmentsWithQuantifier(variable, assignments, false);
    } else {
      return handleInitializationAssignmentsWithoutQuantifier(assignments);
    }
  }

  /**
   * Handles initialization assignments.
   *
   * @param assignments A list of assignment statements.
   * @return A boolean formula for the assignment.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   * @throws InterruptedException It the execution was interrupted.
   */
  private BooleanFormula handleInitializationAssignmentsWithoutQuantifier(
      final List<CExpressionAssignmentStatement> assignments)
      throws UnrecognizedCodeException, InterruptedException {
    BooleanFormula result = conv.bfmgr.makeTrue();
    for (CExpressionAssignmentStatement assignment : assignments) {
      final CLeftHandSide lhs = assignment.getLeftHandSide();
      result =
          conv.bfmgr.and(result, handleAssignment(lhs, lhs, assignment.getRightHandSide(), true));
    }
    return result;
  }

  /**
   * Handles an initialization assignments, i.e. an assignment with a C initializer, with using a
   * quantifier over the resulting SMT array.
   *
   * <p>If we cannot make an assignment of the form {@code <variable> = <value>}, we fall back to
   * the normal initialization in {@link #handleInitializationAssignmentsWithoutQuantifier(List)}.
   *
   * @param pLeftHandSide The left hand side of the statement. Needed for fallback scenario.
   * @param pAssignments A list of assignment statements.
   * @param pUseOldSSAIndices A flag indicating whether we will reuse SSA indices or not.
   * @return A boolean formula for the assignment.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   * @throws InterruptedException If the execution was interrupted.
   * @see #handleInitializationAssignmentsWithoutQuantifier(List)
   */
  private BooleanFormula handleInitializationAssignmentsWithQuantifier(
      final CIdExpression pLeftHandSide,
      final List<CExpressionAssignmentStatement> pAssignments,
      final boolean pUseOldSSAIndices)
      throws UnrecognizedCodeException, InterruptedException {

    assert !pAssignments.isEmpty()
        : "Cannot handle initialization assignments without an assignment right hand side.";

    final CType lhsType = typeHandler.getSimplifiedType(pAssignments.get(0).getLeftHandSide());
    final CType rhsType = typeHandler.getSimplifiedType(pAssignments.get(0).getRightHandSide());

    final CExpressionVisitorWithPointerAliasing rhsVisitor = newExpressionVisitor();
    final Expression rhsValue = pAssignments.get(0).getRightHandSide().accept(rhsVisitor);

    final CExpressionVisitorWithPointerAliasing lhsVisitor = newExpressionVisitor();
    final Location lhsLocation = pLeftHandSide.accept(lhsVisitor).asLocation();

    if (!rhsValue.isValue()
        || !checkEqualityOfInitializers(pAssignments, rhsVisitor)
        || !lhsLocation.isAliased()) {
      // Fallback case, if we have no initialization of the form "<variable> = <value>"
      // Example code snippet
      // (cf. test/programs/simple/struct-initializer-for-composite-field.c)
      //    struct s { int x; };
      //    struct t { struct s s; };
      //    ...
      //    const struct s s = { .x = 1 };
      //    struct t t = { .s = s };
      return handleInitializationAssignmentsWithoutQuantifier(pAssignments);
    } else {
      MemoryRegion region = lhsLocation.asAliased().getMemoryRegion();
      if (region == null) {
        region = regionMgr.makeMemoryRegion(lhsType);
      }
      final String targetName = regionMgr.getPointerAccessName(region);
      final FormulaType<?> targetType = conv.getFormulaTypeFromCType(lhsType);
      final int oldIndex = conv.getIndex(targetName, lhsType, ssa);
      final int newIndex =
          pUseOldSSAIndices
              ? conv.getIndex(targetName, lhsType, ssa)
              : conv.getFreshIndex(targetName, lhsType, ssa);

      final Formula counter =
          fmgr.makeVariableWithoutSSAIndex(
              conv.voidPointerFormulaType, targetName + "__" + oldIndex + "__counter");
      final BooleanFormula rangeConstraint =
          fmgr.makeElementIndexConstraint(
              counter, lhsLocation.asAliased().getAddress(), pAssignments.size(), false);

      final Formula newDereference =
          conv.ptsMgr.makePointerDereference(targetName, targetType, newIndex, counter);
      final Formula rhs =
          conv.makeCast(rhsType, lhsType, rhsValue.asValue().getValue(), constraints, edge);

      final BooleanFormula assignNewValue = fmgr.assignment(newDereference, rhs);

      final BooleanFormula copyOldValue;
      if (options.useArraysForHeap()) {
        final ArrayFormulaManagerView afmgr = fmgr.getArrayFormulaManager();
        final ArrayFormula<?, ?> newArray =
            afmgr.makeArray(targetName, newIndex, conv.voidPointerFormulaType, targetType);
        final ArrayFormula<?, ?> oldArray =
            afmgr.makeArray(targetName, oldIndex, conv.voidPointerFormulaType, targetType);
        copyOldValue = fmgr.makeEqual(newArray, oldArray);

      } else {
        copyOldValue =
            fmgr.assignment(
                newDereference,
                conv.ptsMgr.makePointerDereference(targetName, targetType, oldIndex, counter));
      }

      return fmgr.getQuantifiedFormulaManager()
          .forall(
              counter,
              bfmgr.and(
                  bfmgr.implication(rangeConstraint, assignNewValue),
                  bfmgr.implication(bfmgr.not(rangeConstraint), copyOldValue)));
    }
  }

  /**
   * Checks, whether all assignments of an initializer have the same value.
   *
   * @param pAssignments The list of assignments.
   * @param pRhsVisitor A visitor to evaluate the value of the right-hand side.
   * @return Whether all assignments of an initializer have the same value.
   * @throws UnrecognizedCodeException If the C code was unrecognizable.
   */
  private boolean checkEqualityOfInitializers(
      final List<CExpressionAssignmentStatement> pAssignments,
      final CExpressionVisitorWithPointerAliasing pRhsVisitor)
      throws UnrecognizedCodeException {
    Expression tmp = null;
    for (CExpressionAssignmentStatement assignment : pAssignments) {
      if (tmp == null) {
        tmp = assignment.getRightHandSide().accept(pRhsVisitor);
      }
      if (!tmp.equals(assignment.getRightHandSide().accept(pRhsVisitor))) {
        return false;
      }
    }
    return true;
  }

  @Deprecated
  BooleanFormula makeDestructiveAssignment(
      CType lvalueType,
      CType rvalueType,
      final Location lvalue,
      final Expression rvalue,
      final boolean useOldSSAIndices,
      final @Nullable Set<MemoryRegion> updatedRegions,
      final @Nullable BooleanFormula condition,
      boolean useQuantifiers)
      throws UnrecognizedCodeException {
    return assignmentQuantifierHandler.makeDestructiveAssignment(
        lvalueType,
        rvalueType,
        lvalue,
        rvalue,
        useOldSSAIndices,
        updatedRegions,
        condition,
        useQuantifiers);
  }

  private CExpressionVisitorWithPointerAliasing newExpressionVisitor() {
    // TODO: remove this function
    return assignmentQuantifierHandler.newExpressionVisitor();
  }

}
