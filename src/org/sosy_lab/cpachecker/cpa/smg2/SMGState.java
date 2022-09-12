// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cpa.smg2;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.collect.PathCopyingPersistentTreeMap;
import org.sosy_lab.common.collect.PersistentMap;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CSimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.cfa.types.c.CBasicType;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.core.defaults.LatticeAbstractState;
import org.sosy_lab.cpachecker.core.interfaces.AbstractQueryableState;
import org.sosy_lab.cpachecker.core.interfaces.Graphable;
import org.sosy_lab.cpachecker.cpa.smg.join.SMGJoinStatus;
import org.sosy_lab.cpachecker.cpa.smg.util.PersistentSet;
import org.sosy_lab.cpachecker.cpa.smg.util.PersistentStack;
import org.sosy_lab.cpachecker.cpa.smg2.SMGErrorInfo.Property;
import org.sosy_lab.cpachecker.cpa.smg2.abstraction.SMGCPAMaterializer;
import org.sosy_lab.cpachecker.cpa.smg2.refiner.SMGInterpolant;
import org.sosy_lab.cpachecker.cpa.smg2.util.CFunctionDeclarationAndOptionalValue;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMG2Exception;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMGObjectAndOffset;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMGObjectAndSMGState;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMGStateAndOptionalSMGObjectAndOffset;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMGValueAndSMGState;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMGValueAndSPC;
import org.sosy_lab.cpachecker.cpa.smg2.util.SPCAndSMGObjects;
import org.sosy_lab.cpachecker.cpa.smg2.util.ValueAndValueSize;
import org.sosy_lab.cpachecker.cpa.smg2.util.value.ValueAndSMGState;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.AddressExpression;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.SymbolicExpression;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.SymbolicValueFactory;
import org.sosy_lab.cpachecker.cpa.value.type.NumericValue;
import org.sosy_lab.cpachecker.cpa.value.type.NumericValue.NegativeNaN;
import org.sosy_lab.cpachecker.cpa.value.type.Value;
import org.sosy_lab.cpachecker.cpa.value.type.Value.UnknownValue;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.InvalidQueryException;
import org.sosy_lab.cpachecker.util.refinement.ImmutableForgetfulState;
import org.sosy_lab.cpachecker.util.smg.graph.SMGDoublyLinkedListSegment;
import org.sosy_lab.cpachecker.util.smg.graph.SMGHasValueEdge;
import org.sosy_lab.cpachecker.util.smg.graph.SMGObject;
import org.sosy_lab.cpachecker.util.smg.graph.SMGPointsToEdge;
import org.sosy_lab.cpachecker.util.smg.graph.SMGSinglyLinkedListSegment;
import org.sosy_lab.cpachecker.util.smg.graph.SMGValue;
import org.sosy_lab.cpachecker.util.smg.join.SMGJoinSPC;
import org.sosy_lab.cpachecker.util.states.MemoryLocation;

/**
 * Class holding the SPC (SymbolicProgramConfiguration = memory model) for heap, global
 * variables/constants and the stack. Also provides methods to manipulate the SMG; meaning
 * adding/pruning/reading and memory error/leak handling. This class is meant to represent the
 * CPAState, while the memory state is represented by the SPC. This class therefore hands down
 * write/read and other memory operations. It is expected that in the SPC no CPA specific stuff is
 * handled.
 */
public class SMGState
    implements ImmutableForgetfulState<SMGInformation>,
        LatticeAbstractState<SMGState>,
        AbstractQueryableState,
        Graphable {

  // Properties:
  private static final String HAS_INVALID_FREES = "has-invalid-frees";

  private static final String HAS_INVALID_READS = "has-invalid-reads";

  private static final String HAS_INVALID_WRITES = "has-invalid-writes";

  private static final String HAS_LEAKS = "has-leaks";

  private static final String HAS_HEAP_OBJECTS = "has-heap-objects";

  @SuppressWarnings("unused")
  private static final Pattern externalAllocationRecursivePattern =
      Pattern.compile("^(r_)(\\d+)(_.*)$");

  // All memory models (SMGs) (heap/global/stack)
  private final SymbolicProgramConfiguration memoryModel;

  private final MachineModel machineModel;
  private final LogManager logger;
  private final List<SMGErrorInfo> errorInfo;
  private final SMGOptions options;
  // Transformer for abstracted heap into concrete heap
  private final SMGCPAMaterializer materializer;

  // Constructor only for NEW/EMPTY SMGStates!
  private SMGState(
      MachineModel pMachineModel,
      SymbolicProgramConfiguration spc,
      LogManager logManager,
      SMGOptions opts) {
    memoryModel = spc;
    machineModel = pMachineModel;
    logger = logManager;
    options = opts;
    errorInfo = ImmutableList.of();
    materializer = new SMGCPAMaterializer(logger);
  }

  private SMGState(
      MachineModel pMachineModel,
      SymbolicProgramConfiguration spc,
      LogManager logManager,
      SMGOptions opts,
      List<SMGErrorInfo> errorInf) {
    memoryModel = spc;
    machineModel = pMachineModel;
    logger = logManager;
    options = opts;
    errorInfo = errorInf;
    materializer = new SMGCPAMaterializer(logger);
  }

  @Override
  public Object evaluateProperty(String pProperty) throws InvalidQueryException {
    switch (pProperty) {
      case "toString":
        return toString();
      case "heapObjects":
        return memoryModel.getHeapObjects();
      default:
        // try boolean properties
        return checkProperty(pProperty);
    }
  }

  @Override
  public boolean checkProperty(String pProperty) throws InvalidQueryException {
    switch (pProperty) {
      case HAS_LEAKS:
        if (hasMemoryLeak()) {
          // TODO: Give more information
          issueMemoryError("Memory leak found", false);
          return true;
        }
        return false;
      case HAS_INVALID_WRITES:
        if (hasInvalidWrite()) {
          // TODO: Give more information
          issueMemoryError("Invalid write found", true);
          return true;
        }
        return false;
      case HAS_INVALID_READS:
        if (hasInvalidRead()) {
          // TODO: Give more information
          issueMemoryError("Invalid read found", true);
          return true;
        }
        return false;
      case HAS_INVALID_FREES:
        if (hasInvalidFree()) {
          // TODO: Give more information
          issueMemoryError("Invalid free found", true);
          return true;
        }
        return false;
      case HAS_HEAP_OBJECTS:
        // Having heap objects is not an error on its own.
        // However, when combined with program exit, we can detect property MemCleanup.
        PersistentSet<SMGObject> heapObs = memoryModel.getHeapObjects();
        Preconditions.checkState(
            heapObs.size() >= 1 && heapObs.contains(SMGObject.nullInstance()),
            "NULL must always be a heap object");
        // TODO: check the validity check!
        for (SMGObject object : heapObs) {
          if (!memoryModel.isObjectValid(object)) {
            heapObs = heapObs.removeAndCopy(object);
          }
        }
        return !heapObs.isEmpty();

      default:
        throw new InvalidQueryException("Query '" + pProperty + "' is invalid.");
    }
  }

  private void issueMemoryError(String pMessage, boolean pUndefinedBehavior) {
    if (options.isMemoryErrorTarget()) {
      logger.log(Level.FINE, pMessage);
    } else if (pUndefinedBehavior) {
      logger.log(Level.FINE, pMessage);
      logger.log(
          Level.FINE,
          "Non-target undefined behavior detected. The verification result is unreliable.");
    }
  }

  private boolean hasInvalidWrite() {
    for (SMGErrorInfo errorInf : errorInfo) {
      if (errorInf.isInvalidWrite()) {
        return true;
      }
    }
    return false;
  }

  private boolean hasInvalidRead() {
    for (SMGErrorInfo errorInf : errorInfo) {
      if (errorInf.isInvalidRead()) {
        return true;
      }
    }
    return false;
  }

  private boolean hasInvalidFree() {
    for (SMGErrorInfo errorInf : errorInfo) {
      if (errorInf.isInvalidFree()) {
        return true;
      }
    }
    return false;
  }

  private boolean hasMemoryLeak() {
    for (SMGErrorInfo errorInf : errorInfo) {
      if (errorInf.hasMemoryLeak()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Creates a new, empty {@link SMGState} with the {@link SMGOptions} given. The {@link
   * SymbolicProgramConfiguration} and {@link SMGErrorInfo} inside are new and empty as well. This
   * does not create a stack frame and should only be used for testing!
   *
   * @param pMachineModel the {@link MachineModel} used to determine the size of types.
   * @param logManager {@link LogManager} to log important information.
   * @param opts {@link SMGOptions} to be used.
   * @return a newly created {@link SMGState} with a new and empty {@link
   *     SymbolicProgramConfiguration} inside.
   */
  public static SMGState of(MachineModel pMachineModel, LogManager logManager, SMGOptions opts) {
    return new SMGState(
        pMachineModel,
        SymbolicProgramConfiguration.of(BigInteger.valueOf(pMachineModel.getSizeofPtrInBits())),
        logManager,
        opts);
  }

  /**
   * Creates a new, empty {@link SMGState} with the {@link SMGOptions} given. The {@link
   * SymbolicProgramConfiguration} and {@link SMGErrorInfo} inside are new and empty as well. The
   * given CPA is used to extract the main function if possible and create the inital stack frame
   * automatically.
   *
   * @param pMachineModel the {@link MachineModel} used to determine the size of types.
   * @param logManager {@link LogManager} to log important information.
   * @param opts {@link SMGOptions} to be used.
   * @param pCfa used to extract the main function.
   * @return a newly created {@link SMGState} with a new and empty {@link
   *     SymbolicProgramConfiguration} inside. The only thing added is the inital stack frame if
   *     possible.
   */
  public static SMGState of(
      MachineModel pMachineModel, LogManager logManager, SMGOptions opts, CFA pCfa) {
    FunctionEntryNode pNode = pCfa.getMainFunction();
    return of(pMachineModel, logManager, opts, pNode);
  }

  /**
   * Creates a new, empty {@link SMGState} with the {@link SMGOptions} given. The {@link
   * SymbolicProgramConfiguration} and {@link SMGErrorInfo} inside are new and empty as well. The
   * given CPA is used to extract the main function if possible and create the inital stack frame
   * automatically.
   *
   * @param pMachineModel the {@link MachineModel} used to determine the size of types.
   * @param logManager {@link LogManager} to log important information.
   * @param opts {@link SMGOptions} to be used.
   * @param cfaFunEntryNode main function node from the CFA!
   * @return a newly created {@link SMGState} with a new and empty {@link
   *     SymbolicProgramConfiguration} inside. The only thing added is the inital stack frame if
   *     possible.
   */
  public static SMGState of(
      MachineModel pMachineModel,
      LogManager logManager,
      SMGOptions opts,
      FunctionEntryNode cfaFunEntryNode) {
    SMGState newState = of(pMachineModel, logManager, opts);
    if (cfaFunEntryNode instanceof CFunctionEntryNode) {
      CFunctionEntryNode functionNode = (CFunctionEntryNode) cfaFunEntryNode;
      return newState.copyAndAddStackFrame(functionNode.getFunctionDefinition());
    }
    return newState;
  }

  /**
   * Creates a new, empty {@link SMGState} with the {@link SMGOptions} given. The {@link
   * SymbolicProgramConfiguration} and {@link SMGErrorInfo} inside are new and empty as well. The
   * given CPA is used to extract the main function if possible and create the inital stack frame
   * automatically.
   *
   * @param pMachineModel the {@link MachineModel} used to determine the size of types.
   * @param logManager {@link LogManager} to log important information.
   * @param opts {@link SMGOptions} to be used.
   * @param cfaEntryFunDecl main function declaration from the CFA!
   * @return a newly created {@link SMGState} with a new and empty {@link
   *     SymbolicProgramConfiguration} inside. The only thing added is the inital stack frame if
   *     possible.
   */
  public static SMGState of(
      MachineModel pMachineModel,
      LogManager logManager,
      SMGOptions opts,
      CFunctionDeclaration cfaEntryFunDecl) {
    return of(pMachineModel, logManager, opts).copyAndAddStackFrame(cfaEntryFunDecl);
  }

  /**
   * Creates a new {@link SMGState} out of the parameters given. No new elements are created by
   * this.
   *
   * @param pMachineModel the {@link MachineModel} used to determine the size of types.
   * @param pSPC the {@link SymbolicProgramConfiguration} to be used in the new state.
   * @param logManager the {@link LogManager} to be used in the new state.
   * @param opts {@link SMGOptions} to be used.
   * @param pErrorInfo the {@link SMGErrorInfo} holding error information.
   * @return a new {@link SMGState} with the arguments given.
   */
  public static SMGState of(
      MachineModel pMachineModel,
      SymbolicProgramConfiguration pSPC,
      LogManager logManager,
      SMGOptions opts,
      List<SMGErrorInfo> pErrorInfo) {
    return new SMGState(pMachineModel, pSPC, logManager, opts, pErrorInfo);
  }

  /**
   * Checks the presence of a {@link MemoryLocation} (excluding the offset) as a global or local
   * variable anywhere (not only the current stack frame).
   *
   * @param memLoc this method will only extract the qualified name!
   * @return true if it exists as a variable.
   */
  public boolean isLocalOrGlobalVariablePresent(MemoryLocation memLoc) {
    String qualifiedName = memLoc.getQualifiedName();
    return isGlobalVariablePresent(qualifiedName) || isLocalVariablePresentAnywhere(qualifiedName);
  }

  public boolean isLocalOrGlobalVariablePresent(String qualifiedName) {
    return isGlobalVariablePresent(qualifiedName) || isLocalVariablePresentAnywhere(qualifiedName);
  }

  @SuppressWarnings("unused")
  private SMGState assignReturnValue(
      MemoryLocation memLoc,
      ValueAndValueSize valueAndSize,
      Map<String, BigInteger> variableNameToMemorySizeInBits,
      Map<String, CType> variableTypeMap) {
    SMGState currentState = this;
    SMGObject obj = getReturnObjectForMemoryLocation(memLoc);
    BigInteger offsetToWriteToInBits = BigInteger.valueOf(memLoc.getOffset());
    @Nullable BigInteger sizeOfWriteInBits = valueAndSize.getSizeInBits();
    Preconditions.checkArgument(sizeOfWriteInBits != null);
    Value valueToWrite = valueAndSize.getValue();
    Preconditions.checkArgument(!valueToWrite.isUnknown());
    CType typeOfUnknown = null;
    CType simpleType = variableTypeMap.get(memLoc.getQualifiedName());
    if (simpleType != null && simpleType.getCanonicalType() instanceof CSimpleType) {
      typeOfUnknown = simpleType;
    }
    // TODO: use variableTypeMap and deconstruct struct and array types to the correct ones
    // This is only needed for floats nested in these types btw.
    return currentState.writeValue(
        obj, offsetToWriteToInBits, sizeOfWriteInBits, valueToWrite, typeOfUnknown);
  }

  /*
   *  Checks the existing of the return variable with the entered memLoc.
   *  False if the memloc is no function return.
   *  Our CEGAR algorithm has forced my hand on this.
   */
  private boolean isFunctionReturnVariableAndPresent(MemoryLocation memLoc) {
    if (memLoc.getIdentifier().equals("__retval__")) {
      if (getReturnObjectForMemoryLocation(memLoc) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * Writes the Value given to the variable (local or global) given.
   *
   * @param memLoc Name and offset in bits for the variables to write.
   * @param valueAndSize new Value and size of the type in bits.
   * @param variableNameToMemorySizeInBits the size of the variable in total in bits.
   * @return a new state with given values written to the variable given at the position given.
   * @throws SMG2Exception should never happen in this case as the writes are copies and therefore
   *     save.
   */
  public SMGState assignNonHeapConstant(
      MemoryLocation memLoc,
      ValueAndValueSize valueAndSize,
      Map<String, BigInteger> variableNameToMemorySizeInBits,
      Map<String, CType> variableTypeMap)
      throws SMG2Exception {

    if (isFunctionReturnVariableAndPresent(memLoc)) {
      return assignReturnValue(
          memLoc, valueAndSize, variableNameToMemorySizeInBits, variableTypeMap);
    }
    SMGState currentState = this;
    // Deconstruct MemoryLocation to get the qualified name of global/local vars
    // And remember the offset. Offset + size from ValueAndValueSize are the
    // SMGHasValueEdge information besides the mapping, which is either a new mapping
    // or an old one found in the current mapping
    String qualifiedName = memLoc.getQualifiedName();
    if (!isLocalOrGlobalVariablePresent(memLoc)) {
      // Create the variable first
      BigInteger sizeInBits = variableNameToMemorySizeInBits.get(qualifiedName);
      if (memLoc.isOnFunctionStack()) {
        // Add depending on function stack!
        currentState =
            currentState.copyAndAddLocalVariableToSpecificStackframe(
                memLoc.getFunctionName(),
                sizeInBits,
                qualifiedName,
                variableTypeMap.get(qualifiedName));
      } else {
        currentState =
            currentState.copyAndAddGlobalVariable(
                sizeInBits, qualifiedName, variableTypeMap.get(qualifiedName));
      }
    }
    BigInteger offsetToWriteToInBits = BigInteger.valueOf(memLoc.getOffset());
    @Nullable BigInteger sizeOfWriteInBits = valueAndSize.getSizeInBits();
    Preconditions.checkArgument(sizeOfWriteInBits != null);
    Value valueToWrite = valueAndSize.getValue();
    Preconditions.checkArgument(!valueToWrite.isUnknown());
    // Null is fine because that would only be needed for the unknown case which can't happen
    CType typeOfUnknown = null;
    // Write (easier then inserting everything on its own, and guaranteed to succeed as its a copy
    // from the original state)
    CType simpleType = variableTypeMap.get(memLoc.getQualifiedName());
    if (simpleType != null && simpleType.getCanonicalType() instanceof CSimpleType) {
      typeOfUnknown = simpleType;
    }
    // TODO: use variableTypeMap and deconstruct struct and array types to the correct ones
    // This is only needed for floats nested in these types btw.
    return currentState.writeToAnyStackOrGlobalVariable(
        qualifiedName, offsetToWriteToInBits, sizeOfWriteInBits, valueToWrite, typeOfUnknown);
  }

  private SMGState reconstructStackFrames(
      PersistentStack<CFunctionDeclarationAndOptionalValue> pStackDeclarations) {
    SMGState currentState = this;
    // the given stack is reversed! We can
    Iterator<StackFrame> existingFrames = currentState.memoryModel.getStackFrames().iterator();
    Iterator<CFunctionDeclarationAndOptionalValue> shouldBeFrames = pStackDeclarations.iterator();
    // The current should have the main!
    // The other can be empty for the false/true interpolants
    Preconditions.checkArgument(existingFrames.hasNext());
    while (shouldBeFrames.hasNext()) {
      if (existingFrames.hasNext()) {
        // As long as there are frames on both, they should match
        StackFrame thisFrame = existingFrames.next();
        CFunctionDeclarationAndOptionalValue otherFunDefAndReturnValue = shouldBeFrames.next();
        CFunctionDeclaration otherFunDef = otherFunDefAndReturnValue.getCFunctionDeclaration();
        Preconditions.checkArgument(thisFrame.getFunctionDefinition().equals(otherFunDef));
      } else {
        // Start adding to the current
        CFunctionDeclarationAndOptionalValue otherFunDefAndReturnValue = shouldBeFrames.next();
        CFunctionDeclaration otherFunDef = otherFunDefAndReturnValue.getCFunctionDeclaration();
        currentState = currentState.copyAndAddStackFrame(otherFunDef);
        if (otherFunDefAndReturnValue.hasReturnValue()) {
          currentState = currentState.writeToReturn(otherFunDefAndReturnValue.getReturnValue());
        }
      }
    }

    return currentState;
  }

  /**
   * Verifies that the given {@link MemoryLocation} has the given {@link Value} (with respect to
   * their numerical value only). Used as sanity check for interpolants.
   *
   * @param variableAndOffset Variable name and offset to read in bits.
   * @param valueAndSize expected Value and read size in bits.
   * @return true if the Values match with respect to their numeric interpretation without types.
   *     False else.
   */
  public boolean verifyVariableEqualityWithValueAt(
      MemoryLocation variableAndOffset, ValueAndValueSize valueAndSize) {
    Value expectedValue = valueAndSize.getValue();
    Value readValue = getValueToVerify(variableAndOffset, valueAndSize);
    // Note: asNumericValue() returns null for non numerics
    return expectedValue.asNumericValue().longValue() == readValue.asNumericValue().longValue();
  }

  /* public for debugging purposes in interpolation only! */
  public Value getValueToVerify(MemoryLocation variableAndOffset, ValueAndValueSize valueAndSize) {
    String variableName = variableAndOffset.getQualifiedName();
    BigInteger offsetInBits = BigInteger.valueOf(variableAndOffset.getOffset());
    // Null for new interpolants, return unknown
    @Nullable BigInteger sizeOfReadInBits = valueAndSize.getSizeInBits();
    if (sizeOfReadInBits == null) {
      return UnknownValue.getInstance();
    }

    SMGObject memoryToRead = memoryModel.getObjectForVariable(variableName).orElseThrow();
    return readValue(memoryToRead, offsetInBits, sizeOfReadInBits, null).getValue();
  }

  public SMGState reconstructWhitelistedHeapValues(Set<Value> allowedHeapValues) {
    SMGState currentState = this;
    for (Value heapValue : allowedHeapValues) {
      currentState =
          currentState.copyAndReplaceMemoryModel(
              currentState.getMemoryModel().copyAndAddToheapValueWhitelist(heapValue));
    }
    return currentState;
  }

  /**
   * Removes ALL {@link MemoryLocation}s given from the state and then adds them back in with the
   * values given. The given Values should never represent any heap related Values (pointers).
   *
   * @param nonHeapAssignments {@link MemoryLocation}s and matching {@link ValueAndValueSize} for
   *     each variable to be changed.
   * @param variableNameToMemorySizeInBits the overall size of the variables.
   * @return a new SMGState with all entered variables (MemoryLocations) removed and then
   * @throws SMG2Exception should never be thrown! If it is thrown, then there is a bug.
   */
  public SMGState reconstructSMGStateFromNonHeapAssignments(
      @Nullable PersistentMap<MemoryLocation, ValueAndValueSize> nonHeapAssignments,
      Map<String, BigInteger> variableNameToMemorySizeInBits,
      Map<String, CType> variableTypeMap,
      PersistentStack<CFunctionDeclarationAndOptionalValue> pStackDeclarations,
      Set<Value> allowedHeapValues)
      throws SMG2Exception {
    if (nonHeapAssignments == null) {
      if (pStackDeclarations == null && allowedHeapValues == null) {
        return this;
      }
      return this.reconstructStackFrames(pStackDeclarations)
          .reconstructWhitelistedHeapValues(allowedHeapValues);
    }
    SMGState currentState = this;
    // Reconstruct the stack frames first
    currentState = currentState.reconstructStackFrames(pStackDeclarations);

    for (Entry<MemoryLocation, ValueAndValueSize> entry : nonHeapAssignments.entrySet()) {
      currentState =
          currentState.assignNonHeapConstant(
              entry.getKey(), entry.getValue(), variableNameToMemorySizeInBits, variableTypeMap);
    }
    return currentState.reconstructWhitelistedHeapValues(allowedHeapValues);
  }

  /**
   * Merge the error info of pOther into this {@link SMGState}.
   *
   * @param pOther the state you want the error info from.
   * @return this state with the error info of this + other.
   */
  public SMGState withViolationsOf(SMGState pOther) {
    if (errorInfo.equals(pOther.errorInfo)) {
      return this;
    }
    return of(
        machineModel,
        memoryModel,
        logger,
        options,
        new ImmutableList.Builder<SMGErrorInfo>()
            .addAll(errorInfo)
            .addAll(pOther.errorInfo)
            .build());
  }

  /**
   * Copy SMGState with a newly created object and put it into the global namespace. This replaces
   * an existing old global variable!
   *
   * @param pTypeSizeInBits Size of the type of the new global variable.
   * @param pVarName Name of the global variable.
   * @return Newly created {@link SMGState} with the object added for the name specified.
   */
  public SMGState copyAndAddGlobalVariable(int pTypeSizeInBits, String pVarName, CType type) {
    // TODO: do we really need this for ints?
    return copyAndAddGlobalVariable(BigInteger.valueOf(pTypeSizeInBits), pVarName, type);
  }

  /**
   * Copy SMGState with a newly created object and put it into the global namespace. This replaces
   * an existing old global variable!
   *
   * @param pTypeSizeInBits Size of the type of the new global variable.
   * @param pVarName Name of the global variable.
   * @return Newly created {@link SMGState} with the object added for the name specified.
   */
  public SMGState copyAndAddGlobalVariable(
      BigInteger pTypeSizeInBits, String pVarName, CType type) {
    SMGObject newObject = SMGObject.of(0, pTypeSizeInBits, BigInteger.ZERO);
    return of(
        machineModel,
        memoryModel.copyAndAddGlobalObject(newObject, pVarName, type),
        logger,
        options,
        errorInfo);
  }

  /**
   * Copy SMGState with a newly created {@link SMGObject} and returns the new state + the new {@link
   * SMGObject} with the size specified in bits. Make sure that you reuse the {@link SMGObject}
   * right away to create a points-to-edge and not just use SMGObjects in the code.
   *
   * @param pTypeSizeInBits Size of the type of the new global variable.
   * @return Newly created object + state with it.
   */
  public SMGObjectAndSMGState copyAndAddHeapObject(BigInteger pTypeSizeInBits) {
    SMGObject newObject = SMGObject.of(0, pTypeSizeInBits, BigInteger.ZERO);
    return SMGObjectAndSMGState.of(
        newObject,
        of(machineModel, memoryModel.copyAndAddHeapObject(newObject), logger, options, errorInfo));
  }

  /* Only used by abstraction materialization */
  public SMGState copyAndAddObjectToHeap(SMGObject object) {
    return of(machineModel, memoryModel.copyAndAddHeapObject(object), logger, options, errorInfo);
  }

  // Only to be used by materilization to copy a SMGObject
  public SMGState copyAllValuesFromObjToObj(SMGObject source, SMGObject target) {
    return of(
        machineModel,
        memoryModel.copyAllValuesFromObjToObj(source, target),
        logger,
        options,
        errorInfo);
  }

  // Only to be used by materilization to copy a SMGObject
  // Replace the pointer behind value with a new pointer with the new SMGObject target
  public SMGState replaceAllPointersTowardsWith(SMGValue pointerValue, SMGObject newTarget) {
    return of(
        machineModel,
        memoryModel.replaceAllPointersTowardsWith(pointerValue, newTarget),
        logger,
        options,
        errorInfo);
  }

  /**
   * Copy SMGState with a newly created {@link SMGObject} and returns the new state + the new {@link
   * SMGObject} with the size specified in bits. Make sure that you reuse the {@link SMGObject}
   * right away to create a points-to-edge and not just use SMGObjects in the code.
   *
   * @param pTypeSizeInBits Size of the type of the new global variable.
   * @return Newly created object + state with it.
   */
  public SMGObjectAndSMGState copyAndAddStackObject(BigInteger pTypeSizeInBits) {
    SMGObject newObject = SMGObject.of(0, pTypeSizeInBits, BigInteger.ZERO);
    return SMGObjectAndSMGState.of(
        newObject,
        of(machineModel, memoryModel.copyAndAddStackObject(newObject), logger, options, errorInfo));
  }

  /**
   * Checks if a global variable exists for the name given.
   *
   * @param pVarName Name of the global variable.
   * @return true if the var exists, false else.
   */
  public boolean isGlobalVariablePresent(String pVarName) {
    return memoryModel.getGlobalVariableToSmgObjectMap().containsKey(pVarName);
  }

  /**
   * Checks if a local variable exists for the name given. Note: this checks ALL stack frames.
   *
   * @param pVarName Name of the local variable.
   * @return true if the var exists, false else.
   */
  private boolean isLocalVariablePresentAnywhere(String pVarName) {
    PersistentStack<StackFrame> frames = memoryModel.getStackFrames();
    if (frames == null) {
      return false;
    }
    for (StackFrame stackframe : frames) {
      if (stackframe.getVariables().containsKey(pVarName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Copy SMGState with a newly created object with the size given and put it into the current stack
   * frame. If there is no stack frame this throws an exception!
   *
   * <p>Keeps consistency: yes
   *
   * @param pTypeSize Size of the type the new local variable in bits.
   * @param pVarName Name of the local variable
   * @return {@link SMGState} with the new variables searchable by the name given.
   * @throws SMG2Exception thrown if the stack frame is empty.
   */
  public SMGState copyAndAddLocalVariable(int pTypeSize, String pVarName, CType type)
      throws SMG2Exception {
    if (memoryModel.getStackFrames().isEmpty()) {
      throw new SMG2Exception(
          "Can't add a variable named "
              + pVarName
              + " to the memory model because there is no stack frame.");
    }
    SMGObject newObject = SMGObject.of(0, BigInteger.valueOf(pTypeSize), BigInteger.ZERO);
    return of(
        machineModel,
        memoryModel.copyAndAddStackObject(newObject, pVarName, type),
        logger,
        options,
        errorInfo);
  }

  /**
   * Copy SMGState with a newly created object with the size given and put it into the current stack
   * frame. If there is no stack frame this throws an exception!
   *
   * <p>Keeps consistency: yes
   *
   * @param pTypeSize Size of the type the new local variable in bits.
   * @param pVarName Name of the local variable
   * @return {@link SMGState} with the new variables searchable by the name given.
   * @throws SMG2Exception thrown if the stack frame is empty.
   */
  public SMGState copyAndAddLocalVariable(BigInteger pTypeSize, String pVarName, CType type)
      throws SMG2Exception {
    if (memoryModel.getStackFrames().isEmpty()) {
      throw new SMG2Exception(
          "Can't add a variable named "
              + pVarName
              + " to the memory model because there is no stack frame.");
    }
    SMGObject newObject = SMGObject.of(0, pTypeSize, BigInteger.ZERO);
    return of(
        machineModel,
        memoryModel.copyAndAddStackObject(newObject, pVarName, type),
        logger,
        options,
        errorInfo);
  }

  private SMGState copyAndAddLocalVariableToSpecificStackframe(
      String functionNameForStackFrame, BigInteger pTypeSize, String pVarName, CType type)
      throws SMG2Exception {
    if (memoryModel.getStackFrames().isEmpty()) {
      throw new SMG2Exception(
          "Can't add a variable named "
              + pVarName
              + " to the memory model because there is no stack frame.");
    }
    SMGObject newObject = SMGObject.of(0, pTypeSize, BigInteger.ZERO);
    return of(
        machineModel,
        memoryModel.copyAndAddStackObjectToSpecificStackFrame(
            functionNameForStackFrame, newObject, pVarName, type),
        logger,
        options,
        errorInfo);
  }

  /**
   * Copy SMGState with a newly created anonymous object and put it into the current stack frame.
   * Used for string initilizers as function arguments.
   *
   * <p>Keeps consistency: yes
   *
   * @param pTypeSize Size of the type the new local variable
   * @return Newly created object
   * @throws SMG2Exception thrown if there is no stack frame to add the var to.
   */
  public SMGState copyAndAddAnonymousVariable(int pTypeSize, CType type) throws SMG2Exception {
    return copyAndAddLocalVariable(pTypeSize, makeAnonymousVariableName(), type);
  }

  /**
   * Returns true if there exists a variable on the stack with the name entered.
   *
   * @param pState state to check the memory model for.
   * @param variableName name of the variable.
   * @return true if the variable exists, false otherwise.
   */
  public boolean checkVariableExists(SMGState pState, String variableName) {
    return pState.getMemoryModel().getObjectForVisibleVariable(variableName).isPresent();
  }

  /**
   * Copy SMGState and adds a new frame for the function.
   *
   * <p>Keeps consistency: yes
   *
   * @param pFunctionDefinition A function for which to create a new stack frame
   */
  public SMGState copyAndAddStackFrame(CFunctionDeclaration pFunctionDefinition) {
    return copyAndAddStackFrame(pFunctionDefinition, null);
  }

  /**
   * Copy SMGState and adds a new frame for the function. Also saves the variable arguments of this
   * function. Null as argument means no variable arguments. The list of variable arguments may be
   * empty if var args are possible but not used.
   *
   * @param pFunctionDefinition A function for which to create a new stack frame
   */
  public SMGState copyAndAddStackFrame(
      CFunctionDeclaration pFunctionDefinition,
      @Nullable ImmutableList<Value> variableArgumentsInOrder) {
    return of(
        machineModel,
        memoryModel.copyAndAddStackFrame(
            pFunctionDefinition, machineModel, variableArgumentsInOrder),
        logger,
        options,
        errorInfo);
  }

  @Override
  public String toDOTLabel() {
    // TODO:
    return toString();
  }

  @Override
  public boolean shouldBeHighlighted() {
    return false;
  }

  @Override
  public String getCPAName() {
    return "SMGCPA";
  }

  /*
   * Join 2 SMGStates and as a consequence its SMGs as far as possible.
   */
  @Override
  public SMGState join(SMGState pOther) throws CPAException, InterruptedException {
    SMGJoinSPC joinSPC = new SMGJoinSPC(memoryModel, pOther.memoryModel);
    if (!(joinSPC.getStatus() == SMGJoinStatus.INCOMPARABLE && joinSPC.isDefined())) {
      return pOther;
    }
    // return new SMGState(machineModel, joinSPC.getResult(), logger, options);
    return this;
  }

  @Override
  public boolean isLessOrEqual(SMGState pOther) throws CPAException, InterruptedException {
    // also, this element is not less or equal than the other element, if it contains less elements
    if (getSize() < pOther.getSize()) {
      return false;
    }

    // also, this element is not less or equal than the other element,
    // if any one constant's value of the other element differs from the constant's value in this
    // element
    Iterator<CFunctionDeclarationAndOptionalValue> thisStackFrames =
        this.memoryModel.getFunctionDeclarationsFromStackFrames().iterator();
    Iterator<CFunctionDeclarationAndOptionalValue> otherStackFrames =
        pOther.memoryModel.getFunctionDeclarationsFromStackFrames().iterator();
    while (otherStackFrames.hasNext()) {
      if (!thisStackFrames.hasNext()) {
        return false;
      }
      CFunctionDeclarationAndOptionalValue thisFrame = thisStackFrames.next();
      CFunctionDeclarationAndOptionalValue otherFrame = otherStackFrames.next();
      if (otherFrame.hasReturnValue()) {
        Value otherRetVal = otherFrame.getReturnValue();
        if (!thisFrame.hasReturnValue()) {
          return false;
        }
        Value thisRetVal = thisFrame.getReturnValue();
        if (!areValuesEqual(this, thisRetVal, pOther, otherRetVal, ImmutableSet.of())) {
          return false;
        }
      } else {
        if (thisFrame.hasReturnValue()) {
          return false;
        }
      }
    }

    // the tolerant way: ignore all type information.
    PersistentMap<MemoryLocation, ValueAndValueSize> memLocAndValues =
        memoryModel.getMemoryLocationsAndValuesForSPCWithoutHeap();
    for (Entry<MemoryLocation, ValueAndValueSize> otherEntry :
        pOther.memoryModel.getMemoryLocationsAndValuesForSPCWithoutHeap().entrySet()) {
      MemoryLocation key = otherEntry.getKey();
      Value otherValue = otherEntry.getValue().getValue();
      ValueAndValueSize thisValueAndType = memLocAndValues.get(key);
      if (thisValueAndType == null
          || !areValuesEqual(
              this, thisValueAndType.getValue(), pOther, otherValue, ImmutableSet.of())) {
        return false;
      }
    }

    return true;
  }

  private boolean areValuesEqual(
      SMGState thisState,
      @Nullable Value thisValue,
      SMGState otherState,
      @Nullable Value otherValue,
      Set<Value> thisAlreadyChecked)
      throws SMG2Exception {
    // Comparing pointers leads to == true, but they may be not equal because of the heap!!!
    if (thisValue == otherValue && thisValue.isExplicitlyKnown()) {
      return true;
    }
    if (otherValue == null || thisValue == null) {
      return false;
    }

    if (thisValue.isNumericValue() && otherValue.isNumericValue()) {
      Number thisNum = thisValue.asNumericValue().getNumber();
      Number otherNum = otherValue.asNumericValue().getNumber();
      if (thisNum.getClass() != otherNum.getClass()) {
        return false;
      } else if (thisNum instanceof Float
          && (((Float) thisNum).isNaN() || ((Float) otherNum).isNaN())) {
        return false;
      } else if (thisNum instanceof Double
          && (((Double) thisNum).isNaN() || ((Double) otherNum).isNaN())) {
        return false;
      }
      return thisNum.equals(otherNum);
    }

    // Pointers are more difficult, they are represented by a SymbolicIdentifier, again unique
    // id. We need to use the CPA method
    if (memoryModel.isPointer(thisValue) && otherState.memoryModel.isPointer(otherValue)) {
      // Pointers can be cyclic! We remember already checked values.
      if (thisAlreadyChecked.contains(thisValue)) {
        return true;
      } else {
        return isHeapEqualForTwoPointersWithTwoStates(
            thisState,
            thisValue,
            otherState,
            otherValue,
            ImmutableSet.<Value>builder().addAll(thisAlreadyChecked).add(thisValue).build());
      }
    }

    // Unknowns in this current CPA implementation are not comparable in different states!
    // Each state generates a unique ConstantSymbolicExpression id (as its statically generated)
    // Comparable is only that both are ConstantSymbolicExpressions and the type matches and
    // that they do represent the same location
    if (thisValue instanceof SymbolicExpression
        && otherValue instanceof SymbolicExpression
        && ((SymbolicExpression) thisValue)
            .getType()
            .equals(((SymbolicExpression) otherValue).getType())) {
      if (options.isAssignSymbolicValues()) {
        return true;
      } else {
        return thisValue.equals(otherValue);
      }
    }

    return thisValue.equals(otherValue);
  }

  /* Check heap equality as far as possible. This has some limitations.
   * We just check the shape and known values/pointers. */
  private boolean isHeapEqualForTwoPointersWithTwoStates(
      SMGState thisState,
      Value thisAddress,
      SMGState otherState,
      Value otherAddress,
      Set<Value> thisAlreadyChecked)
      throws SMG2Exception {
    // Careful, dereference might materialize new memory out of abstractions!
    Optional<SMGStateAndOptionalSMGObjectAndOffset> thisDeref =
        thisState.dereferencePointerWithoutMaterilization(thisAddress);
    Optional<SMGStateAndOptionalSMGObjectAndOffset> otherDeref =
        otherState.dereferencePointerWithoutMaterilization(otherAddress);
    if (thisDeref.equals(otherDeref)) {
      // Empty, zero object or truly the same. We generate Objects with unique ids statically, so
      // they are only equal if they are truly equal.
      return true;
    }
    if (thisDeref.isPresent() && otherDeref.isPresent()) {
      SMGStateAndOptionalSMGObjectAndOffset thisDerefObjAndOffset = thisDeref.orElseThrow();
      SMGStateAndOptionalSMGObjectAndOffset otherDerefObjAndOffset = otherDeref.orElseThrow();
      thisState = thisDerefObjAndOffset.getSMGState();
      otherState = otherDerefObjAndOffset.getSMGState();
      SMGObject thisObj = thisDerefObjAndOffset.getSMGObject();
      SMGObject otherObj = otherDerefObjAndOffset.getSMGObject();
      if (thisObj.equals(otherObj)) {
        return true;
      }
      if (thisDerefObjAndOffset
              .getOffsetForObject()
              .compareTo(otherDerefObjAndOffset.getOffsetForObject())
          != 0) {
        return false;
      } else if (!thisState
          .memoryModel
          .getPointerSpecifier(thisAddress)
          .equals(otherState.memoryModel.getPointerSpecifier(otherAddress))) {
        return false;
      } else if (!(thisObj.getSize().compareTo(otherObj.getSize()) == 0
          && thisObj.getNestingLevel() == otherObj.getNestingLevel()
          && thisObj.getOffset().compareTo(otherObj.getOffset()) == 0)) {
        return false;
      }
      if (thisObj instanceof SMGDoublyLinkedListSegment) {
        if (otherObj instanceof SMGDoublyLinkedListSegment) {
          // We know at this point that the segments are the same size and have the same specifier
          // etc. The values need to be checked, independent of the pointers
          // this <= other min length is the most important
          SMGDoublyLinkedListSegment thisDLL = (SMGDoublyLinkedListSegment) thisObj;
          SMGDoublyLinkedListSegment otherDLL = (SMGDoublyLinkedListSegment) otherObj;
          if (thisDLL.getMinLength() >= otherDLL.getMinLength()
              && thisDLL.getNextOffset().compareTo(otherDLL.getNextOffset()) == 0
              && thisDLL.getPrevOffset().compareTo(otherDLL.getPrevOffset()) == 0
              && thisDLL.getHeadOffset().compareTo(otherDLL.getHeadOffset()) == 0) {
            // Check that the values are equal and that the back pointer is as well
            return checkEqualValuesForTwoStatesWithExcemptions(
                thisDLL,
                otherDLL,
                ImmutableList.of(thisDLL.getNextOffset(), thisDLL.getPrevOffset()),
                thisState,
                otherState,
                thisAlreadyChecked);
          }
        } else {
          return false;
        }
      } else if (thisObj instanceof SMGSinglyLinkedListSegment) {
        if (otherObj instanceof SMGSinglyLinkedListSegment) {
          SMGSinglyLinkedListSegment thisSLL = (SMGSinglyLinkedListSegment) thisObj;
          SMGSinglyLinkedListSegment otherSLL = (SMGSinglyLinkedListSegment) otherObj;
          if (thisSLL.getMinLength() >= otherSLL.getMinLength()
              && thisSLL.getNextOffset().compareTo(otherSLL.getNextOffset()) == 0
              && thisSLL.getHeadOffset().compareTo(otherSLL.getHeadOffset()) == 0) {
            // Check that the values are equal and that the back pointer is as well
            return checkEqualValuesForTwoStatesWithExcemptions(
                thisSLL,
                otherSLL,
                ImmutableList.of(thisSLL.getNextOffset()),
                thisState,
                otherState,
                thisAlreadyChecked);
          }
        } else {
          return false;
        }
      }
      return checkEqualValuesForTwoStatesWithExcemptions(
          thisObj, otherObj, ImmutableList.of(), thisState, otherState, thisAlreadyChecked);
    }
    return false;
  }

  // Interpret the SMGValues as Values or else they are not comparable!!!!
  // The nfo offset is ignored.
  private boolean checkEqualValuesForTwoStatesWithExcemptions(
      SMGObject thisObject,
      SMGObject otherObject,
      ImmutableList<BigInteger> excemptOffsets,
      SMGState thisState,
      SMGState otherState,
      Set<Value> thisAlreadyChecked)
      throws SMG2Exception {
    FluentIterable<SMGHasValueEdge> thisHVEs;
    FluentIterable<SMGHasValueEdge> otherHVEs;
    if (excemptOffsets.isEmpty()) {
      thisHVEs = thisState.memoryModel.getSmg().getHasValueEdgesByPredicate(thisObject, hv -> true);
      otherHVEs =
          otherState.memoryModel.getSmg().getHasValueEdgesByPredicate(otherObject, e -> true);
    } else {
      thisHVEs =
          thisState
              .memoryModel
              .getSmg()
              .getHasValueEdgesByPredicate(
                  thisObject, e -> !excemptOffsets.contains(e.getOffset()));
      otherHVEs =
          otherState
              .memoryModel
              .getSmg()
              .getHasValueEdgesByPredicate(
                  otherObject, e -> !excemptOffsets.contains(e.getOffset()));
    }
    if (otherHVEs.size() > thisHVEs.size()) {
      return false;
    }

    Map<BigInteger, SMGHasValueEdge> thisOffsetToHVEdgeMap = new HashMap<>();
    for (SMGHasValueEdge hve : thisHVEs) {
      thisOffsetToHVEdgeMap.put(hve.getOffset(), hve);
    }
    for (SMGHasValueEdge otherHVE : otherHVEs) {
      BigInteger otherOffset = otherHVE.getOffset();
      SMGHasValueEdge thisHVE = thisOffsetToHVEdgeMap.get(otherOffset);
      if (thisHVE == null || thisHVE.getSizeInBits().compareTo(otherHVE.getSizeInBits()) != 0) {
        return false;
      }
      // Check the Value (not the SMGValue!). If a SMGValue exists, a Value mapping exists.
      Value otherHVEValue =
          otherState.memoryModel.getValueFromSMGValue(otherHVE.hasValue()).orElseThrow();
      Value thisHVEValue =
          thisState.memoryModel.getValueFromSMGValue(thisHVE.hasValue()).orElseThrow();
      // These values are either numeric, pointer or unknown
      if (!areValuesEqual(thisState, thisHVEValue, otherState, otherHVEValue, thisAlreadyChecked)) {
        return false;
      }
    }
    return true;
  }

  public boolean hasMemoryErrors() {
    for (SMGErrorInfo info : errorInfo) {
      if (info.hasMemoryErrors()) {
        return true;
      }
    }
    return false;
  }

  public boolean hasMemoryLeaks() {
    for (SMGErrorInfo info : errorInfo) {
      if (info.hasMemoryLeak()) {
        return true;
      }
    }
    return false;
  }

  /*
   * Check non-equality of the 2 entered potential addresses. Never use == or equals on addresses!
   * Tries to prove the not equality of two given addresses. Returns true if the prove of
   * not equality succeeded, returns false if both are potentially equal.
   * This method expects the Values to be the actual addresses and NOT AddressExpressions!
   */
  public boolean areNonEqualAddresses(Value pValue1, Value pValue2) {
    Optional<SMGValue> smgValue1 = memoryModel.getSMGValueFromValue(pValue1);
    Optional<SMGValue> smgValue2 = memoryModel.getSMGValueFromValue(pValue2);
    if (smgValue1.isEmpty() || smgValue2.isEmpty()) {
      // The return value should not matter here as this is checked before
      return true;
    }
    return memoryModel.proveInequality(smgValue1.orElseThrow(), smgValue2.orElseThrow());
  }

  /** Logs the error entered using the states logger. */
  private void logMemoryError(String pMessage, boolean pUndefinedBehavior) {
    if (options.isMemoryErrorTarget()) {
      logger.log(Level.FINE, pMessage);
    } else if (pUndefinedBehavior) {
      logger.log(Level.FINE, pMessage);
      logger.log(
          Level.FINE,
          "Non-target undefined behavior detected. The verification result is unreliable.");
    }
  }

  private static int anonymousVarCount = 0;

  private static String makeAnonymousVariableName() {
    return "anonymous_var_" + anonymousVarCount++;
  }

  public SMGState copyAndPruneOutOfScopeVariables(Set<CSimpleDeclaration> pOutOfScopeVars) {
    SMGState retState = this;
    for (CSimpleDeclaration variable : pOutOfScopeVars) {
      retState = retState.copyAndPruneVariable(MemoryLocation.forDeclaration(variable));
    }

    return retState;
  }

  public SMGState copyAndReplaceMemoryModel(SymbolicProgramConfiguration newSPC) {
    return of(machineModel, newSPC, logger, options, errorInfo);
  }

  private SMGState copyAndPruneVariable(MemoryLocation pMemoryLocation) {
    if (pMemoryLocation.isOnFunctionStack()) {
      return copyAndPruneFunctionStackVariable(pMemoryLocation);
    } else {
      return copyAndPruneGlobalVariable(pMemoryLocation);
    }
  }

  private SMGState copyAndPruneGlobalVariable(MemoryLocation pMemoryLocation) {
    return of(
        machineModel,
        memoryModel.copyAndRemoveGlobalVariable(pMemoryLocation.getIdentifier()),
        logger,
        options,
        errorInfo);
  }

  private SMGState copyAndPruneFunctionStackVariable(MemoryLocation pMemoryLocation) {
    return of(
        machineModel,
        memoryModel.copyAndRemoveStackVariable(pMemoryLocation.getQualifiedName()),
        logger,
        options,
        errorInfo);
  }

  // Only public for builtin functions
  public SMGState copyAndPruneFunctionStackVariable(String variableName) {
    return of(
        machineModel,
        memoryModel.copyAndRemoveStackVariable(variableName),
        logger,
        options,
        errorInfo);
  }

  public SMGState dropStackFrame() {
    return of(machineModel, memoryModel.copyAndDropStackFrame(), logger, options, errorInfo);
  }

  /*
   * Copy the current state and prune all unreachable SMGObjects. Used for example after a function return to prune out of scope memory. This also detects memory leaks and updates the error state if one is found!
   */
  public SMGState copyAndPruneUnreachable() {
    SPCAndSMGObjects newHeapAndUnreachables = memoryModel.copyAndPruneUnreachable();
    SymbolicProgramConfiguration newHeap = newHeapAndUnreachables.getSPC();
    Collection<SMGObject> unreachableObjects = newHeapAndUnreachables.getSMGObjects();

    if (unreachableObjects.isEmpty()) {
      return this;
    }

    return copyAndReplaceMemoryModel(newHeap).copyWithMemLeak(unreachableObjects);
  }

  /*
   * Remove the entered object from the heap and general memory mappings.
   * Also all has-value-edges are pruned. Nothing else.
   */
  public SMGState copyAndRemoveObjectFromHeap(SMGObject obj) {
    return copyAndReplaceMemoryModel(memoryModel.copyAndRemoveObjectFromHeap(obj));
  }

  /*
   * Copy the state with an error attached. This method is used for memory leaks, meaning its a non fatal error.
   */
  private SMGState copyWithMemLeak(Collection<SMGObject> leakedObjects) {
    String leakedObjectsLabels =
        leakedObjects.stream().map(Object::toString).collect(Collectors.joining(","));
    String errorMSG = "Memory leak of " + leakedObjectsLabels + " is detected.";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_HEAP)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(leakedObjects);
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  // TODO: invalid read/write because of invalidated object/memory

  /**
   * Use of an variable that was not initialized. The value will be unknown, but generally
   * undefined.
   *
   * @param uninitializedVariableName the {@link String} that is not initialized.
   * @return A new {@link SMGState} with the error info.
   */
  public SMGState withUninitializedVariableUsage(String uninitializedVariableName) {
    String errorMSG =
        "Usage of uninitialized variable: "
            + uninitializedVariableName
            + ". A unknown value was assumed, but behavior is of this variable is generally"
            + " undefined.";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_READ)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(uninitializedVariableName));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Error for dereferencing unknown pointer {@link Value} when reading. I.e. int bla = *value; with
   * value being unknown.
   *
   * @param unknownAddress the {@link Value} that is unknown to the memory model and was tried to be
   *     dereferenced.
   * @return A new {@link SMGState} with the error info.
   */
  public SMGState withUnknownPointerDereferenceWhenReading(Value unknownAddress) {
    String errorMSG = "Unknown value pointer dereference for value: " + unknownAddress + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_READ)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(unknownAddress));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Error for dereferencing unknown pointer {@link Value} when reading with intent to write. I.e.
   * *value = 3; with value being unknown.
   *
   * @param unknownAddress the {@link Value} that is unknown to the memory model and was tried to be
   *     dereferenced.
   * @return A new {@link SMGState} with the error info.
   */
  public SMGState withUnknownPointerDereferenceWhenWriting(Value unknownAddress) {
    String errorMSG =
        "Unknown value pointer dereference with intent to write to it for value: "
            + unknownAddress
            + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_READ)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(unknownAddress));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Error for trying to write to a local/global variable that is not declared.
   *
   * @param variableName the variable name of the variable that was tried to write to.
   * @return A new {@link SMGState} with the error info.
   */
  public SMGState withWriteToUnknownVariable(String variableName) {
    String errorMSG = "Failed write to variable " + variableName + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_WRITE)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(variableName));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * The error sais invalid read as the point that fails is the read of the {@link SMGObject} before
   * writing! I.e. *pointer = ...; With pointer failing to dereference because its pointing to 0.
   *
   * @param nullObject the {@link SMGObject} that is null and was tried to be dereferenced.
   * @return A new SMGState with the error info.
   */
  public SMGState withNullPointerDereferenceWhenWriting(SMGObject nullObject) {
    // Get the SMGValue and Value that lead to this null pointer dereference
    String errorMSG =
        "Null pointer dereference on read of object with the intent to write to: "
            + nullObject
            + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_READ)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(nullObject));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Copy the state with a memory leak error set.
   *
   * @param errorMsg custom error message specific to the error reason.
   * @param pUnreachableObjects the object at fault.
   * @return a copy of the current state with the error info added.
   */
  public SMGState withMemoryLeak(String errorMsg, Collection<Object> pUnreachableObjects) {
    // TODO: replace Object; currently it is only used by Value (address to SMGObject)
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_HEAP)
            .withErrorMessage(errorMsg)
            .withInvalidObjects(pUnreachableObjects);
    // Log the error in the logger
    logMemoryError(errorMsg, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Invalid write to a not initialized, unknown or non-existing or beyond the boundries of a memory
   * region.
   *
   * @param invalidAddress the invalid address pointing to nothing.
   * @return A new SMGState with the error info.
   */
  public SMGState withInvalidWrite(Value invalidAddress) {
    String errorMSG =
        "Write to invalid, unknown or non-existing memory region, pointed to by: "
            + invalidAddress
            + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_WRITE)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(invalidAddress));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Invalid write to a not initialized, unknown or non-existing or beyond the boundries of a memory
   * region.
   *
   * @param invalidWriteRegion the invalid address pointing to nothing.
   * @return A new SMGState with the error info.
   */
  public SMGState withInvalidWrite(SMGObject invalidWriteRegion) {
    String errorMSG =
        "Write to invalid, unknown or non-existing or beyond the boundries of a memory region: "
            + invalidWriteRegion
            + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_WRITE)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(invalidWriteRegion));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Invalid write to 0. (0 SMGObject)
   *
   * @param invalidWriteRegion the invalid address pointing to nothing.
   * @return A new SMGState with the error info.
   */
  public SMGState withInvalidWriteToZeroObject(SMGObject invalidWriteRegion) {
    String errorMSG = "Write to invalid memory region: NULL.";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_WRITE)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(invalidWriteRegion));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Invalid write with custom error msg.
   *
   * @param invalidValue the invalid value. Either address or write value or something like a size
   *     specifier etc.
   * @return A new SMGState with the error info.
   */
  public SMGState withInvalidWrite(String errorMSG, Value invalidValue) {
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_WRITE)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(invalidValue));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * I.e. int bla = *pointer; With pointer failing to dereference because its pointing to 0.
   *
   * @param nullObject the {@link SMGObject} that is null and was tried to be dereferenced.
   * @return A new SMGState with the error info.
   */
  public SMGState withNullPointerDereferenceWhenReading(SMGObject nullObject) {
    // getValueForSMGValue(pValue)
    // Get the SMGValue and Value that lead to this null pointer dereference
    String errorMSG =
        "Null pointer dereference on read of object with the intent to read it: "
            + nullObject
            + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_READ)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(nullObject));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * I.e. int bla = blub; With blub not existing or having no memory.
   *
   * @param readVariable the variable that was tried to be read.
   * @return A new SMGState with the error info.
   */
  public SMGState withInvalidStackVariableRead(String readVariable) {
    String errorMSG = "Invalid read of variable named: " + readVariable + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_READ)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(readVariable));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * General read outside of memory boundries.
   *
   * @param readMemory the memory {@link SMGObject} that was tried to be read.
   * @return A new SMGState with the error info.
   */
  public SMGState withInvalidRead(SMGObject readMemory) {
    String errorMSG = "Invalid read of memory object: " + readMemory + ".";
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_READ)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(readMemory));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Copy and update this {@link SMGState} with an error resulting from trying to write outside of
   * the range of the {@link SMGObject}. Returns an updated state with the error in it.
   *
   * @param objectWrittenTo the {@link SMGObject} that should have been written to.
   * @param writeOffset The offset in bits where you want to write the {@link Value} to.
   * @param writeSize the size of the {@link Value} in bits.
   * @param pValue the {@link Value} you wanted to write.
   * @return A new SMGState with the error info.
   */
  public SMGState withOutOfRangeWrite(
      SMGObject objectWrittenTo, BigInteger writeOffset, BigInteger writeSize, Value pValue) {
    String errorMSG =
        String.format(
            "Try writing value %s with size %d at offset %d bit to object sized %d bit.",
            pValue.toString(), writeSize, writeOffset, objectWrittenTo.getSize());
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_WRITE)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(objectWrittenTo));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Copy and update this {@link SMGState} with an error resulting from trying to read outside of
   * the range of the {@link SMGObject}. Returns an updated state with the error in it.
   *
   * @param objectRead the {@link SMGObject} that should have been read.
   * @param readOffset The offset in bits as {@link BigInteger} where you want to read.
   * @param readSize the size of the type in bits to read as {@link BigInteger}.
   * @return A new SMGState with the error info.
   */
  public SMGState withOutOfRangeRead(
      SMGObject objectRead, BigInteger readOffset, BigInteger readSize) {
    String errorMSG =
        String.format(
            "Try reading object %s with size %d bits at offset %d bit with read type size %d bit",
            objectRead, objectRead.getSize(), readOffset, readSize);
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_READ)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(objectRead));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Log undefined behavior.
   *
   * @param errorMSG custom error msg.
   * @param reason the reasons for the undefined behavior. I.e. invalid memcpy pointers.
   * @return a new state with the error attached.
   */
  public SMGState withUndefinedbehavior(String errorMSG, Collection<Object> reason) {
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.UNDEFINED_BEHAVIOR)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(reason);
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Copy and update this {@link SMGState} with an error resulting from trying to free a address
   * {@link Value} invalidly. Returns an updated state with the error in it.
   *
   * @param errorMSG the error message.
   * @param invalidValue the {@link Value} that was invalidly freed.
   * @return A new SMGState with the error info.
   */
  public SMGState withInvalidFree(String errorMSG, Value invalidValue) {
    SMGErrorInfo newErrorInfo =
        SMGErrorInfo.of()
            .withProperty(Property.INVALID_FREE)
            .withErrorMessage(errorMSG)
            .withInvalidObjects(Collections.singleton(invalidValue));
    // Log the error in the logger
    logMemoryError(errorMSG, true);
    return copyWithNewErrorInfo(newErrorInfo);
  }

  /**
   * Returns a copy of this {@link SMGState} with the entered SPC and a new {@link SMGErrorInfo}
   * added.
   *
   * @param pErrorInfo The new {@link SMGErrorInfo} tied to the returned state.
   * @return a copy of the {@link SMGState} this is based on with the newly entered SPC and error
   *     info.
   */
  public SMGState copyWithNewErrorInfo(SMGErrorInfo pErrorInfo) {
    return of(
        machineModel,
        memoryModel,
        logger,
        options,
        new ImmutableList.Builder<SMGErrorInfo>().addAll(errorInfo).add(pErrorInfo).build());
  }

  /**
   * @return memory model, including Heap, stack and global vars.
   */
  public SymbolicProgramConfiguration getMemoryModel() {
    return memoryModel;
  }

  /**
   * Add the {@link Value} mapping if it was not mapped to a {@link SMGValue}, if it was already
   * present the state is unchanged and the known {@link SMGValue} returned. The {@link SMGValue} is
   * not added to the SPC yet, writeValue() will do that.
   *
   * @param pValue the {@link Value} you want to add to the SPC.
   * @return a copy of the current {@link SMGState} with the mapping of the {@link Value} to its
   *     {@link SMGValue} entered if it was not mapped, if it was already present the state is
   *     unchanged and the known {@link SMGValue} returned.
   */
  public SMGValueAndSMGState copyAndAddValue(Value pValue) {
    Optional<SMGValue> maybeValue = memoryModel.getSMGValueFromValue(pValue);
    if (maybeValue.isPresent()) {
      return SMGValueAndSMGState.of(this, maybeValue.orElseThrow());
    } else {
      SMGValue newSMGValue = SMGValue.of();
      return SMGValueAndSMGState.of(
          of(
              machineModel,
              memoryModel.copyAndPutValue(pValue, newSMGValue),
              logger,
              options,
              errorInfo),
          newSMGValue);
    }
  }

  @SuppressWarnings("unused")
  public SMGState addElementToCurrentChain(SMGObject pVariableObject) {
    // TODO Auto-generated method stub
    return this;
  }

  @SuppressWarnings("unused")
  public SMGState addElementToCurrentChain(ValueAndSMGState pResult) {
    // TODO Auto-generated method stub
    return null;
  }

  public List<SMGErrorInfo> getErrorInfo() {
    return errorInfo;
  }

  /**
   * Determines the SMGRegion object which is pointed by a given Value address representation.
   * Return Null SMGObject if there is no such existing address. (will result in null deref later,
   * but not here!) TODO: do we need unknown derefs here?
   *
   * @param pValue - the given Value representation of the address.
   * @return the SMGObject which the address points to, or empty if none is found.
   */
  public Optional<SMGObjectAndOffset> getPointsToTarget(Value pValue) {
    Optional<SMGValue> addressOptional = memoryModel.getSMGValueFromValue(pValue);
    if (addressOptional.isPresent()) {
      Optional<SMGPointsToEdge> pointerEdgeOptional =
          memoryModel.getSmg().getPTEdge(addressOptional.orElseThrow());
      if (pointerEdgeOptional.isPresent()) {
        return Optional.of(
            SMGObjectAndOffset.of(
                pointerEdgeOptional.orElseThrow().pointsTo(),
                pointerEdgeOptional.orElseThrow().getOffset()));
      }
    }
    return Optional.empty();
  }

  /*
   * Transforms any AddressExpression into a new AddressExpression but with offset 0 and
   * a potentially new memory location (pointer) with the offset incorporated. Reuses
   * existing pointers before creating new ones. Always returns the entered value for
   * offset == 0. But unknown for unknown offsets.
   */
  private ValueAndSMGState searchOrCreateAddressForAddressExpr(Value pValue) {
    if (pValue instanceof AddressExpression) {
      AddressExpression addressExprValue = (AddressExpression) pValue;
      Value offsetAddr = addressExprValue.getOffset();
      if (offsetAddr.isNumericValue()) {
        BigInteger offsetAddrBI = offsetAddr.asNumericValue().bigInteger();
        if (offsetAddrBI.compareTo(BigInteger.ZERO) != 0) {
          Optional<SMGObjectAndOffset> maybeTargetAndOffset =
              getPointsToTarget(addressExprValue.getMemoryAddress());
          if (maybeTargetAndOffset.isEmpty()) {
            return ValueAndSMGState.ofUnknownValue(this);
          }
          SMGObjectAndOffset targetAndOffset = maybeTargetAndOffset.orElseThrow();

          SMGObject target = targetAndOffset.getSMGObject();
          BigInteger offsetPointer = targetAndOffset.getOffsetForObject();
          BigInteger offsetOverall = offsetPointer.add(offsetAddrBI);
          // search for existing pointer first and return if found; else make a new one
          ValueAndSMGState addressAndState = searchOrCreateAddress(target, offsetOverall);
          return ValueAndSMGState.of(
              AddressExpression.withZeroOffset(
                  addressAndState.getValue(), addressExprValue.getType()),
              addressAndState.getState());
        }
      } else {
        return ValueAndSMGState.ofUnknownValue(this);
      }
    }
    return ValueAndSMGState.of(pValue, this);
  }

  /**
   * Takes a target and offset and tries to find a address (not AddressExpression) that fits them.
   * If none can be found a new address (SMGPointsToEdge) is created and returned as Value (Not
   * AddressExpression).
   *
   * @param targetObject {@link SMGObject} target.
   * @param offsetInBits Offset as BigInt.
   * @return a {@link Value} (NOT AddressExpression) and state with the address/address added.
   */
  public ValueAndSMGState searchOrCreateAddress(SMGObject targetObject, BigInteger offsetInBits) {
    // search for existing pointer first and return if found
    Optional<SMGValue> maybeAddressValue =
        getMemoryModel().getAddressValueForPointsToTarget(targetObject, offsetInBits);

    if (maybeAddressValue.isPresent()) {
      Optional<Value> valueForSMGValue =
          getMemoryModel().getValueFromSMGValue(maybeAddressValue.orElseThrow());
      // Reuse pointer; there should never be a SMGValue without counterpart!
      // TODO: this might actually be expensive, check once this runs!
      return ValueAndSMGState.of(valueForSMGValue.orElseThrow(), this);
    }

    Value addressValue = SymbolicValueFactory.getInstance().newIdentifier(null);
    SMGState newState = createAndAddPointer(addressValue, targetObject, offsetInBits);
    return ValueAndSMGState.of(addressValue, newState);
  }

  /**
   * Read the value in the {@link SMGObject} at the position specified by the offset and size.
   * Checks for validity of the object and if its externally allocated and may fail because of that.
   * The read {@link SMGValue} will be translated into a {@link Value}. If the Value is known, the
   * known value is used, unknown symbolic else.
   *
   * @param pObject {@link SMGObject} where to read. May not be 0.
   * @param pFieldOffset {@link BigInteger} offset.
   * @param pSizeofInBits {@link BigInteger} sizeInBits.
   * @param readType the {@link CType} of the read. Not casted! Null for irrelevant types.
   * @return The {@link Value} read and the {@link SMGState} after the read.
   */
  public ValueAndSMGState readValue(
      SMGObject pObject,
      BigInteger pFieldOffset,
      BigInteger pSizeofInBits,
      @Nullable CType readType) {
    if (!memoryModel.isObjectValid(pObject) && !memoryModel.isObjectExternallyAllocated(pObject)) {
      return ValueAndSMGState.of(UnknownValue.getInstance(), withInvalidRead(pObject));
    }
    SMGValueAndSPC valueAndNewSPC = memoryModel.readValue(pObject, pFieldOffset, pSizeofInBits);
    // Try to translate the SMGValue to a Value or create a new mapping (the same read on the same
    // object/offset/size yields the same SMGValue, so it should return the same Value)
    SMGState newState = copyAndReplaceMemoryModel(valueAndNewSPC.getSPC());
    // Only use the new state for changes!
    SMGValue readSMGValue = valueAndNewSPC.getSMGValue();
    Optional<Value> maybeValue = newState.getMemoryModel().getValueFromSMGValue(readSMGValue);
    if (maybeValue.isPresent()) {
      // The Value to the SMGValue is already known, use it
      Preconditions.checkArgument(!(maybeValue.orElseThrow() instanceof AddressExpression));
      Value valueRead = maybeValue.orElseThrow();
      if (readType != null && doesRequireUnionFloatConversion(valueRead, readType)) {
        // Float conversion is limited to the Java float types at the moment.
        // Larger float types are almost always unknown
        valueRead = castValueForUnionFloatConversion(valueRead, readType);
      }
      return ValueAndSMGState.of(valueRead, newState);
    }
    // If there is no Value for the SMGValue, we need to create it as an unknown, map it and return
    Value unknownValue = getNewSymbolicValueForType(readType);
    return ValueAndSMGState.of(
        unknownValue,
        copyAndReplaceMemoryModel(
            newState.getMemoryModel().copyAndPutValue(unknownValue, readSMGValue)));
  }

  /*
   * Only to be used by abstraction!
   */
  public SMGValueAndSMGState readSMGValue(
      SMGObject pObject, BigInteger pFieldOffset, BigInteger pSizeofInBits) {
    SMGValueAndSPC valueAndNewSPC = memoryModel.readValue(pObject, pFieldOffset, pSizeofInBits);
    SMGState newState = copyAndReplaceMemoryModel(valueAndNewSPC.getSPC());
    return SMGValueAndSMGState.of(newState, valueAndNewSPC.getSMGValue());
  }

  private boolean doesRequireUnionFloatConversion(Value valueRead, CType readType) {
    if (!valueRead.isNumericValue()) {
      return false;
    }
    if (readType instanceof CSimpleType) {
      // if only one of them is no integer type, a conversion is necessary
      return isFloatingPointType(valueRead) != isFloatingPointType(readType);
    } else {
      return false;
    }
  }

  private boolean isFloatingPointType(CType pType) {
    if (pType instanceof CSimpleType) {
      return ((CSimpleType) pType).getType().isFloatingPointType();
    }
    return false;
  }

  private boolean isFloatingPointType(Value value) {
    if (!value.isNumericValue()) {
      return false;
    }
    Number num = value.asNumericValue().getNumber();
    return num instanceof Float || num instanceof Double || num == NegativeNaN.VALUE;
  }

  /**
   * The only important thing is that the expectedType is NOT the left hand side type or any cast
   * type, but the type of the read before any casts etc.! *
   */
  private Value castValueForUnionFloatConversion(Value readValue, CType expectedType) {
    if (readValue.isNumericValue()) {
      if (isFloatingPointType(readValue)) {
        return extractFloatingPointValueAsIntegralValue(readValue);
      } else if (isFloatingPointType(expectedType.getCanonicalType())
          && !isFloatingPointType(readValue)) {
        return extractIntegralValueAsFloatingPointValue(expectedType.getCanonicalType(), readValue);
      } else {
        return readValue;
      }
    }

    return UnknownValue.getInstance();
  }

  private Value extractFloatingPointValueAsIntegralValue(Value readValue) {
    Number numberValue = readValue.asNumericValue().getNumber();

    if (numberValue instanceof Float) {
      float floatValue = numberValue.floatValue();
      int intBits = Float.floatToIntBits(floatValue);

      return new NumericValue(BigInteger.valueOf(intBits));
    } else if (numberValue instanceof Double) {
      double doubleValue = numberValue.doubleValue();
      long longBits = Double.doubleToLongBits(doubleValue);

      return new NumericValue(BigInteger.valueOf(longBits));
    }

    return UnknownValue.getInstance();
  }

  private Value extractIntegralValueAsFloatingPointValue(CType pReadType, Value readValue) {
    if (pReadType instanceof CSimpleType) {
      CBasicType basicReadType = ((CSimpleType) pReadType.getCanonicalType()).getType();
      NumericValue numericValue = readValue.asNumericValue();

      if (basicReadType.equals(CBasicType.FLOAT)) {
        int bits = numericValue.bigInteger().intValue();
        float floatValue = Float.intBitsToFloat(bits);

        return new NumericValue(floatValue);
      } else if (basicReadType.equals(CBasicType.DOUBLE)) {
        long bits = numericValue.bigInteger().longValue();
        double doubleValue = Double.longBitsToDouble(bits);

        return new NumericValue(doubleValue);
      }
    }
    return UnknownValue.getInstance();
  }

  /**
   * This performs a call to free(addressToFree) with addressToFree being a {@link Value} that
   * should be a address to a memory region, but can be any Value. This method determines if the
   * valueToFree is a valid, not yet freed address and frees the memory behind it, returning the
   * {@link SMGState} with the freed memory. It might however return a state with an error info
   * attached, for example double free. In case of a null-pointer being freed, the logger logs the
   * event without errors.
   *
   * @param addressToFree any {@link Value} thought to be a pointer to a memory region, but it may
   *     be not. It might be a {@link AddressExpression} as well.
   * @param pFunctionCall debug / logging info.
   * @param cfaEdge debug / logging info.
   * @return a new {@link SMGState} with the memory region behind the {@link Value} freed.
   * @throws SMG2Exception in case of critical errors in the concretization of memory.
   */
  public SMGState free(Value addressToFree, CFunctionCallExpression pFunctionCall, CFAEdge cfaEdge)
      throws SMG2Exception {
    Value sanitizedAddressToFree = addressToFree;
    BigInteger baseOffset = BigInteger.ZERO;
    // if the entered value is a AddressExpression think of it as a internal wrapper of pointer +
    // offset. We use the value as pointer and then add the offset to the found offset! If however
    // the offset is non numeric we can't calculate if the free is valid or not.
    if (addressToFree instanceof AddressExpression) {
      // We just disassamble the AddressExpression and use it as if it were a normal pointer
      AddressExpression addressExpr = (AddressExpression) addressToFree;
      sanitizedAddressToFree = addressExpr.getMemoryAddress();

      if (!addressExpr.getOffset().isNumericValue()) {
        // TODO: return a freed and a unfreed state?
        return this;
      }
      baseOffset = addressExpr.getOffset().asNumericValue().bigInteger();
    }

    // Value == 0 can happen by user input and is valid!
    if (sanitizedAddressToFree.isNumericValue()
        && sanitizedAddressToFree.asNumericValue().bigInteger().compareTo(BigInteger.ZERO) == 0) {
      logger.log(
          Level.INFO,
          pFunctionCall.getFileLocation(),
          ":",
          "The argument of a free invocation:",
          cfaEdge.getRawStatement(),
          "is 0");
      return this;
    }

    Optional<SMGStateAndOptionalSMGObjectAndOffset> maybeRegion =
        dereferencePointer(sanitizedAddressToFree);
    // If there is no region, the Optional is empty
    if (maybeRegion.isEmpty()) {
      logger.log(
          Level.INFO,
          "Free on expression ",
          pFunctionCall.getParameterExpressions().get(0).toASTString(),
          " is invalid, because the target of the address could not be calculated.");
      SMGState invalidFreeState =
          withInvalidFree(
              "Free on expression "
                  + pFunctionCall.getParameterExpressions().get(0).toASTString()
                  + " is invalid, because the target of the address could not be"
                  + " calculated.",
              addressToFree);
      return invalidFreeState;
    }
    SMGState currentState = maybeRegion.orElseThrow().getSMGState();
    SMGObject regionToFree = maybeRegion.orElseThrow().getSMGObject();
    BigInteger offsetInBits = baseOffset.add(maybeRegion.orElseThrow().getOffsetForObject());

    // free(0) is a nop in C
    if (regionToFree.isZero()) {
      logger.log(
          Level.INFO,
          pFunctionCall.getFileLocation(),
          ":",
          "The argument of a free invocation:",
          cfaEdge.getRawStatement(),
          "is 0");
      return currentState;
    }

    if (!memoryModel.isHeapObject(regionToFree)
        && !memoryModel.isObjectExternallyAllocated(regionToFree)) {
      // You may not free any objects not on the heap.
      return currentState.withInvalidFree(
          "Invalid free of unallocated object is found.", addressToFree);
    }

    if (!memoryModel.isObjectValid(regionToFree)) {
      // you may not invoke free multiple times on the same object
      return currentState.withInvalidFree(
          "Free has been used on this memory before.", addressToFree);
    }

    if (offsetInBits.compareTo(BigInteger.ZERO) != 0
        && !currentState.memoryModel.isObjectExternallyAllocated(regionToFree)) {
      // you may not invoke free on any address that you
      // didn't get through a malloc, calloc or realloc invocation.
      // (undefined behavour, same as double free)

      return currentState.withInvalidFree(
          "Invalid free as a pointer was used that was not returned by malloc, calloc or realloc.",
          addressToFree);
    }

    // Perform free by invalidating the object behind the address and delete all its edges.
    SymbolicProgramConfiguration newSPC =
        currentState.memoryModel.invalidateSMGObject(regionToFree);
    // TODO: is a consistency check needed? As far as i understand we never enter a inconsistent
    // state in our implementation.
    // performConsistencyCheck(SMGRuntimeCheck.HALF);
    return currentState.copyAndReplaceMemoryModel(newSPC);
  }

  /**
   * Don't use this method outside of this class or tests! Writes into the given {@link SMGObject}
   * at the specified offset in bits with the size in bits the value given. This method adds the
   * Value <-> SMGValue mapping if none is known, else it uses a existing mapping. Make sure that
   * all checks are made before using this! I.e. size checks. (The reason why they are not made here
   * is that sometimes you need to write a lot of values but only need 1 check for the size)
   *
   * @param object the memory {@link SMGObject} to write to.
   * @param writeOffsetInBits offset in bits to be written
   * @param sizeInBits size in bits to be written
   * @param valueToWrite the value to write. Is automatically either translated to a known SMGValue
   *     or a new SMGValue is added to the returned state.
   * @return a new SMGState with the value written.
   */
  protected SMGState writeValue(
      SMGObject object,
      BigInteger writeOffsetInBits,
      BigInteger sizeInBits,
      Value valueToWrite,
      CType type) {
    if (object.isZero()) {
      // Write to 0
      return withInvalidWriteToZeroObject(object);
    }
    SMGState currentState;
    if (valueToWrite instanceof AddressExpression) {
      ValueAndSMGState valueToWriteAndState = transformAddressExpression(valueToWrite);
      valueToWrite = valueToWriteAndState.getValue();
      currentState = valueToWriteAndState.getState();
    }
    if (valueToWrite.isUnknown()) {
      valueToWrite = getNewSymbolicValueForType(type);
    }
    Preconditions.checkArgument(!(valueToWrite instanceof AddressExpression));
    SMGValueAndSMGState valueAndState = copyAndAddValue(valueToWrite);
    SMGValue smgValue = valueAndState.getSMGValue();
    currentState = valueAndState.getSMGState();
    return currentState.writeValue(object, writeOffsetInBits, sizeInBits, smgValue);
  }

  /*
   * If you are wondering why there are so many writes;
   * this is to optimize the checks and variableName <-> SMGObject and
   * Value <-> SMGValue mappings. I don't want to do unneeded checks multiple times.
   * This is public only for abstraction and tests!!!!.
   */
  public SMGState writeValue(
      SMGObject object,
      BigInteger writeOffsetInBits,
      BigInteger sizeInBits,
      SMGValue valueToWrite) {

    return copyAndReplaceMemoryModel(
        memoryModel.writeValue(object, writeOffsetInBits, sizeInBits, valueToWrite));
  }

  /**
   * Writes the Value given to the memory reserved for the return statement of an stack frame. Make
   * sure that there is a return object before calling this. This will check sizes before writing
   * and will map the Value to a SMGValue if there is no mapping. This always assumes offset = 0.
   *
   * @param sizeInBits the size of the Value to write in bits.
   * @param valueToWrite the {@link Value} to write.
   * @return a new {@link SMGState} with either an error info in case of an error or the value
   *     written to the return memory.
   */
  public SMGState writeToReturn(BigInteger sizeInBits, Value valueToWrite, CType returnValueType) {
    SMGObject returnObject = getMemoryModel().getReturnObjectForCurrentStackFrame().orElseThrow();
    if (valueToWrite.isUnknown()) {
      valueToWrite = getNewSymbolicValueForType(returnValueType);
    }
    // Check that the target can hold the value
    if (returnObject.getOffset().compareTo(BigInteger.ZERO) > 0
        || returnObject.getSize().compareTo(sizeInBits) < 0) {
      // Out of range write
      return withOutOfRangeWrite(returnObject, BigInteger.ZERO, sizeInBits, valueToWrite);
    }
    return writeValue(returnObject, BigInteger.ZERO, sizeInBits, valueToWrite, returnValueType);
  }

  /** Writes the value exactly to the size of the return of the current stack frame. * */
  private SMGState writeToReturn(Value valueToWrite) {
    SMGObject returnObject = memoryModel.getReturnObjectForCurrentStackFrame().orElseThrow();
    return writeValue(returnObject, BigInteger.ZERO, returnObject.getSize(), valueToWrite, null);
  }

  /**
   * Writes the entered {@link Value} to the {@link SMGObject} at the specified offset with the
   * specified size both in bits. It can be used for heap and stack, it jsut assumes that the {@link
   * SMGObject} exist in the SPC, so make sure beforehand! The Value will either add or find its
   * {@link SMGValue} counterpart automatically. Also this checks that the {@link SMGObject} is
   * large enough for the write. If something fails, this throws an exception with an error info
   * inside the state thrown with.
   *
   * @param object the {@link SMGObject} to write to.
   * @param writeOffsetInBits the offset in bits for the write of the value.
   * @param sizeInBits size of the written value in bits.
   * @param valueToWrite {@link Value} that gets written into the SPC. Will be mapped to a {@link
   *     SMGValue} automatically.
   * @param valueType {@link CType} of the value to be written. Use the expression type, not the
   *     canonical!
   * @return new {@link SMGState} with the value written to the object.
   * @throws SMG2Exception if something goes wrong. I.e. the sizes of the write don't match with the
   *     size of the object.
   */
  public SMGState writeValueTo(
      SMGObject object,
      BigInteger writeOffsetInBits,
      BigInteger sizeInBits,
      Value valueToWrite,
      CType valueType)
      throws SMG2Exception {
    if (object.isZero()) {
      // Write to 0
      return this.withInvalidWriteToZeroObject(object);
    } else if (!memoryModel.isObjectValid(object)) {
      // Write to an object that is invalidated (already freed)
      return this.withInvalidWrite(object);
    }
    if (valueToWrite.isUnknown()) {
      valueToWrite = getNewSymbolicValueForType(valueType);
    }
    // Check that the target can hold the value
    if (object.getOffset().compareTo(writeOffsetInBits) > 0
        || object.getSize().compareTo(sizeInBits.add(writeOffsetInBits)) < 0) {
      // Out of range write
      // throw new SMG2Exception(
      //     withOutOfRangeWrite(object, writeOffsetInBits, sizeInBits, valueToWrite));
      return withOutOfRangeWrite(object, writeOffsetInBits, sizeInBits, valueToWrite);
    }

    return writeValue(object, writeOffsetInBits, sizeInBits, valueToWrite, valueType);
  }

  /**
   * Writes the entered {@link Value} to the region that the addressToMemory points to at the
   * specified offset with the specified size both in bits. It can be used for heap and stack, it
   * just assumes that the {@link SMGObject} exist in the SPC, so make sure beforehand! The Value
   * will either add or find its {@link SMGValue} counterpart automatically. Also this checks that
   * the {@link SMGObject} is large enough for the write. If something fails, this throws an
   * exception with an error info inside the state thrown with.
   *
   * @param addressToMemory the {@link Value} representing the address of the region to write to.
   * @param writeOffsetInBits the offset in bits for the write of the value.
   * @param sizeInBits size of the written value in bits.
   * @param valueToWrite {@link Value} that gets written into the SPC. Will be mapped to a {@link
   *     SMGValue} automatically.
   * @param valueType the type of the value to be written. Used for unknown values only, to
   *     translate them into a symbolic value.
   * @return new {@link SMGState} with the value written to the object.
   * @throws SMG2Exception if something goes wrong. I.e. the sizes of the write don't match with the
   *     size of the object.
   */
  public SMGState writeValueTo(
      Value addressToMemory,
      BigInteger writeOffsetInBits,
      BigInteger sizeInBits,
      Value valueToWrite,
      CType valueType)
      throws SMG2Exception {
    Optional<SMGStateAndOptionalSMGObjectAndOffset> maybeRegion =
        dereferencePointer(addressToMemory);
    if (maybeRegion.isEmpty()) {
      // Can't write to non existing memory. However, we might not track that memory at the moment!
      return this;
    }

    SMGStateAndOptionalSMGObjectAndOffset memoryRegionAndOffset = maybeRegion.orElseThrow();
    SMGState currentState = memoryRegionAndOffset.getSMGState();
    SMGObject memoryRegion = memoryRegionAndOffset.getSMGObject();
    Preconditions.checkArgument(
        memoryRegionAndOffset.getOffsetForObject().compareTo(BigInteger.ZERO) == 0);

    return currentState.writeValueTo(
        memoryRegion, writeOffsetInBits, sizeInBits, valueToWrite, valueType);
  }

  /**
   * Writes the memory, that is accessed by dereferencing the pointer (address) of the {@link Value}
   * given, completely to 0.
   *
   * @param addressToMemory {@link Value} that is a address pointing to a memory region.
   * @return the new {@link SMGState} with the memory region pointed to by the address written 0
   *     completely.
   * @throws SMG2Exception if there is no memory/or pointer for the given Value.
   */
  public SMGState writeToZero(Value addressToMemory, CType type) throws SMG2Exception {
    Optional<SMGStateAndOptionalSMGObjectAndOffset> maybeRegion =
        dereferencePointer(addressToMemory);
    if (maybeRegion.isEmpty()) {
      // Can't write to non existing memory. However, we might not track that memory at the moment!
      // TODO: log
      return this;
    }
    SMGStateAndOptionalSMGObjectAndOffset memoryRegionAndOffset = maybeRegion.orElseThrow();
    SMGState currentState = memoryRegionAndOffset.getSMGState();
    SMGObject memoryRegion = memoryRegionAndOffset.getSMGObject();
    Preconditions.checkArgument(
        memoryRegionAndOffset.getOffsetForObject().compareTo(BigInteger.ZERO) == 0);
    return currentState.writeValue(
        memoryRegion,
        memoryRegionAndOffset.getOffsetForObject(),
        memoryRegion.getSize(),
        new NumericValue(0),
        type);
  }

  /**
   * Copies the content (Values) of the source {@link SMGObject} starting from the sourceOffset into
   * the target {@link SMGObject} starting from the target offset. This copies until the size limit
   * is reached. If a edge starts within the size, but ends outside, its not copied. This expects
   * that both the source and target exist in the SPC and all checks are made before calling this.
   * These checks should include range checks, overlapping memorys etc.
   *
   * @param sourceObject {@link SMGObject} from which is to be copied.
   * @param sourceStartOffset offset from which the copy is started.
   * @param targetObject target {@link SMGObject}
   * @param targetStartOffset target offset, this is the start of the writes in the target.
   * @param copySizeInBits maximum copied bits. If a edge starts within the accepted range but ends
   *     outside, its not copied.
   * @return {@link SMGState} with the content of the source copied into the target.
   */
  public SMGState copySMGObjectContentToSMGObject(
      SMGObject sourceObject,
      BigInteger sourceStartOffset,
      SMGObject targetObject,
      BigInteger targetStartOffset,
      BigInteger copySizeInBits) {
    SMGState currentState = this;
    BigInteger maxReadOffsetPlusSize = sourceStartOffset.add(copySizeInBits);
    // Removal of edges in the target is not necessary as the write deletes old overlapping edges
    // Get all source edges and copy them
    Set<SMGHasValueEdge> sourceContents = memoryModel.getSmg().getEdges(sourceObject);
    for (SMGHasValueEdge edge : sourceContents) {
      BigInteger edgeOffsetInBits = edge.getOffset();
      BigInteger edgeSizeInBits = edge.getSizeInBits();
      // We only write edges that are >= the beginning offset of the source and edgeOffsetInBits +
      // edgeSizeInBits < sourceStartOffset + copySizeInBits
      if (sourceStartOffset.compareTo(edgeOffsetInBits) <= 0
          && edgeOffsetInBits.add(edgeSizeInBits).compareTo(maxReadOffsetPlusSize) <= 0) {
        // We need to take the targetOffset to source offset difference into account
        BigInteger finalWriteOffsetInBits =
            edgeOffsetInBits.subtract(sourceStartOffset).add(targetStartOffset);
        SMGValue value = edge.hasValue();
        currentState =
            currentState.writeValue(targetObject, finalWriteOffsetInBits, edgeSizeInBits, value);
      }
    }

    return currentState;
  }

  /**
   * Write to a stack (or global) variable with the name given. This method assumes that the
   * variable exists!!!! The offset and size are in bits. The {@link Value} will be added as a
   * {@link SMGValue} mapping if not known.
   *
   * @param variableName name of the variable that should be known already.
   * @param writeOffsetInBits in bits.
   * @param writeSizeInBits in bits.
   * @param valueToWrite {@link Value} to write. If its not yet known as a {@link SMGValue} then the
   *     mapping will be added.
   * @return a {@link SMGState} with the {@link Value} written at the given position in the variable
   *     given.
   * @throws SMG2Exception if the write is out of range or invalid due to the variable being
   *     unknown.
   */
  public SMGState writeToStackOrGlobalVariable(
      String variableName,
      BigInteger writeOffsetInBits,
      BigInteger writeSizeInBits,
      Value valueToWrite,
      CType valueType)
      throws SMG2Exception {
    Optional<SMGObject> maybeVariableMemory =
        getMemoryModel().getObjectForVisibleVariable(variableName);

    if (maybeVariableMemory.isEmpty()) {
      // Write to unknown variable
      return withWriteToUnknownVariable(variableName);
    }

    SMGObject variableMemory = maybeVariableMemory.orElseThrow();
    if (variableMemory.getOffset().compareTo(writeOffsetInBits) > 0
        || variableMemory.getSize().compareTo(writeSizeInBits.add(writeOffsetInBits)) < 0) {
      // Out of range write
      throw new SMG2Exception(
          withOutOfRangeWrite(variableMemory, writeOffsetInBits, writeSizeInBits, valueToWrite));
    }

    return writeValue(variableMemory, writeOffsetInBits, writeSizeInBits, valueToWrite, valueType);
  }

  /* Helper method to reconstruct the state after interpolation. This writes to ANY local variable, independent of stack frame */
  private SMGState writeToAnyStackOrGlobalVariable(
      String variableName,
      BigInteger writeOffsetInBits,
      BigInteger writeSizeInBits,
      Value valueToWrite,
      @Nullable CType valueType) {
    // expected to never be empty!
    Optional<SMGObject> maybeVariableMemory = getMemoryModel().getObjectForVariable(variableName);

    SMGObject variableMemory = maybeVariableMemory.orElseThrow();
    // Expected to be always in range
    return writeValue(variableMemory, writeOffsetInBits, writeSizeInBits, valueToWrite, valueType);
  }

  /**
   * Transforms the entered Value into a non AddressExpression. If the entered Value is none, the
   * entered Value is returned. If the entered Value is a AddressExpression it is transformed into a
   * single Value representing the complete address (with offset). If the offset is non numeric, a
   * unknown value is returned.
   *
   * @param value might be AddressExpression.
   * @return a non AddressExpression Value.
   */
  ValueAndSMGState transformAddressExpression(Value value) {
    if (value instanceof AddressExpression) {
      ValueAndSMGState valueToWriteAndState = searchOrCreateAddressForAddressExpr(value);
      // The returned Value might be a non AddressExpression
      Value valueToWrite = valueToWriteAndState.getValue();
      SMGState currentState = valueToWriteAndState.getState();
      if (valueToWrite instanceof AddressExpression) {
        Preconditions.checkArgument(
            ((AddressExpression) valueToWrite)
                    .getOffset()
                    .asNumericValue()
                    .bigInteger()
                    .compareTo(BigInteger.ZERO)
                == 0);
        valueToWrite = ((AddressExpression) valueToWrite).getMemoryAddress();
        return ValueAndSMGState.of(valueToWrite, currentState);
      } else {
        return ValueAndSMGState.of(valueToWrite, currentState);
      }
    }
    return ValueAndSMGState.of(value, this);
  }

  /**
   * Writes the entire variable given to 0. Same as writeToStackOrGlobalVariable() else.
   *
   * @param variableName name of the variable that should be known already.
   * @return a {@link SMGState} with the {@link Value} wirrten at the given position in the variable
   *     given.
   * @throws SMG2Exception in case of errors like write to not declared variable.
   */
  public SMGState writeToStackOrGlobalVariableToZero(String variableName, CType type)
      throws SMG2Exception {
    Optional<SMGObject> maybeVariableMemory =
        getMemoryModel().getObjectForVisibleVariable(variableName);

    if (maybeVariableMemory.isEmpty()) {
      // Write to unknown variable
      throw new SMG2Exception(withWriteToUnknownVariable(variableName));
    }

    SMGObject variableMemory = maybeVariableMemory.orElseThrow();
    return writeValue(
        variableMemory,
        variableMemory.getOffset(),
        variableMemory.getSize(),
        new NumericValue(0),
        type);
  }

  /**
   * Creates a pointer (points-to-edge) from the value to the target at the specified offset. The
   * Value is mapped to a SMGValue if no mapping exists, else the existing will be used. This does
   * not check whether or not a pointer already exists but will override the target if the value
   * already has a mapping!
   *
   * @param addressValue {@link Value} used as address pointing to the target at the offset.
   * @param target {@link SMGObject} where the pointer points to.
   * @param offsetInBits offset in the object.
   * @return the new {@link SMGState} with the pointer and mapping added.
   */
  public SMGState createAndAddPointer(
      Value addressValue, SMGObject target, BigInteger offsetInBits) {
    return copyAndReplaceMemoryModel(
        memoryModel.copyAndAddPointerFromAddressToRegion(addressValue, target, offsetInBits));
  }

  /**
   * Sets the entered variable to extern. The variable has to exist in the current memory model or
   * an exception is thrown. This keeps the former association of the variable intact! So if its
   * declared global before, it is still after this method.
   *
   * @param variableName name of the variable.
   * @return new {@link SMGState} with the variable set to external.
   */
  public SMGState setExternallyAllocatedFlag(String variableName) {
    return copyAndReplaceMemoryModel(
        memoryModel.copyAndAddExternalObject(
            getMemoryModel().getObjectForVisibleVariable(variableName).orElseThrow()));
  }

  /**
   * Returns a new symbolic constant value. This is meant to transform UNKNOWN Values into usable
   * values with unknown value but known type.
   *
   * @param valueType the {@link CType} of the Value. Don't use the canonical type if possible!
   * @return a new symbolic Value.
   */
  private Value getNewSymbolicValueForType(CType valueType) {
    // For unknown values we use a new symbolic value without memory location as this is
    // handled by the SMGs
    SymbolicValueFactory factory = SymbolicValueFactory.getInstance();
    return factory.asConstant(factory.newIdentifier(null), valueType);
  }

  /**
   * Searches for an existing SMGObject holding the address to a function. We treat function
   * addresses as global variables.
   *
   * @param pDeclaration the {@link CFunctionDeclaration} of the function you are searching for.
   *     Doubles as name in an encoded form similar to qualified name.
   * @return Either a {@link SMGObject} for the function, or empty.
   */
  public Optional<SMGObject> getObjectForFunction(CFunctionDeclaration pDeclaration) {
    String functionQualifiedSMGName = getUniqueFunctionName(pDeclaration);
    return memoryModel.getObjectForVisibleVariable(functionQualifiedSMGName);
  }

  /**
   * Generates a (global) variable for the entered function.
   *
   * @param pDeclaration {@link CFunctionDeclaration} of the function that should be put into a
   *     variable.
   * @return a copy of the SMGState with the variable for the function added.
   */
  public SMGState copyAndAddFunctionVariable(CFunctionDeclaration pDeclaration) {
    String functionQualifiedSMGName = getUniqueFunctionName(pDeclaration);
    return copyAndAddGlobalVariable(0, functionQualifiedSMGName, pDeclaration.getType());
  }

  /*
   * Generates a unique String based on the entered function declaration. Can be used as variable name.
   */
  public String getUniqueFunctionName(CFunctionDeclaration pDeclaration) {

    StringBuilder functionName = new StringBuilder(pDeclaration.getQualifiedName());

    for (CParameterDeclaration parameterDcl : pDeclaration.getParameters()) {
      functionName.append("_");
      functionName.append(CharMatcher.anyOf("* ").replaceFrom(parameterDcl.toASTString(), "_"));
    }

    return "__" + functionName;
  }

  /*
   * Generates a unique String based on the entered function declaration for variable arguments.
   * Can be used as variable name.
   */
  public String getUniqueFunctionBasedNameForVarArgs(CFunctionDeclaration pDeclaration) {
    return getUniqueFunctionName(pDeclaration) + "_varArgs";
  }

  @Override
  public Set<MemoryLocation> getTrackedMemoryLocations() {
    return memoryModel.getMemoryLocationsAndValuesForSPCWithoutHeap().keySet();
  }

  /**
   * A Map of Values in the heap with explicit values and their SMGValue counterparts.
   *
   * @return A Map of Values with explicit values and their SMGValue counterparts.
   */
  public Map<Value, SMGValue> getTrackedHeapValues() {
    ImmutableMap.Builder<Value, SMGValue> trackedHeapValues = ImmutableMap.builder();
    PersistentMap<SMGObject, PersistentSet<SMGHasValueEdge>> valuesOfObj =
        memoryModel.getSmg().getSMGObjectsWithSMGHasValueEdges();
    ImmutableSet.Builder<SMGValue> smgValues = ImmutableSet.builder();
    for (SMGObject heapObj : memoryModel.getHeapObjects()) {
      if (memoryModel.isObjectValid(heapObj) && valuesOfObj.containsKey(heapObj)) {
        for (SMGHasValueEdge hve : valuesOfObj.get(heapObj)) {
          smgValues.add(hve.hasValue());
        }
      }
    }
    for (SMGValue smgValue : smgValues.build()) {
      // We expect all SMGValues to always be known as Values
      Value value = memoryModel.getValueFromSMGValue(smgValue).orElseThrow();
      // filter out unknowns and pointers
      if (value.isExplicitlyKnown()) {
        trackedHeapValues.put(value, smgValue);
      }
    }
    return trackedHeapValues.build();
  }

  @Override
  public int getSize() {
    // Note: this might be inaccurate! We track Strings and functions as encoded variables!
    return memoryModel.getNumberOfVariables();
  }

  public SMGInterpolant createInterpolant() {
    PersistentStack<CFunctionDeclarationAndOptionalValue> funDecls =
        memoryModel.getFunctionDeclarationsFromStackFrames();
    Iterator<CFunctionDeclarationAndOptionalValue> funDeclsIter = funDecls.iterator();
    Preconditions.checkArgument(funDeclsIter.hasNext());

    return new SMGInterpolant(
        options,
        machineModel,
        logger,
        memoryModel.getMemoryLocationsAndValuesForSPCWithoutHeap(),
        memoryModel.getSizeObMemoryForSPCWithoutHeap(),
        memoryModel.getVariableTypeMap(),
        funDecls,
        funDeclsIter.next().getCFunctionDeclaration(),
        memoryModel.getheapValueWhitelist());
  }

  @Deprecated
  @Override
  public void remember(MemoryLocation pLocation, SMGInformation pForgottenInformation) {
    throw new UnsupportedOperationException();
  }

  @Deprecated
  @Override
  public SMGInformation forget(MemoryLocation pLocation) {
    throw new UnsupportedOperationException();
  }

  /**
   * Takes a Value that should be removed from the Heap and replaced by a unknown value. Also takes
   * the SMGValue mapping for it.
   *
   * @param value the (concrete) {@link Value} to remove from all of the heap.
   * @param smgValueForValue the {@link SMGValue} mapping to the {@link Value}.
   * @return StateAndInfo with the new {@link SMGState} with the removed heap values and {@link
   *     SMGInformation} with the info to return the state to its previous state.
   */
  public StateAndInfo<SMGState, SMGInformation> copyAndForget(
      Value value, SMGValue smgValueForValue) {
    // Changing the value mappings would not work, as we would change existing values in local and
    // global variables as well
    // We search for the SMGValues in the Has-Value-Edges and replace them by a new unique symbolic
    // value == unknown
    // We remember the mapping however. It suffices to remember Object -> old Has-Value-Edge

    ImmutableMap.Builder<SMGObject, Set<SMGHasValueEdge>> removedHVEdgesBuilder =
        ImmutableMap.builder();
    for (SMGObject heapObject : memoryModel.getHeapObjects()) {
      if (memoryModel.isObjectValid(heapObject)) {
        FluentIterable<SMGHasValueEdge> iterable =
            memoryModel
                .getSmg()
                .getHasValueEdgesByPredicate(
                    heapObject, n -> n.hasValue().equals(smgValueForValue));
        if (!iterable.isEmpty()) {
          removedHVEdgesBuilder.put(heapObject, iterable.toSet());
        }
      }
    }
    ImmutableMap<SMGObject, Set<SMGHasValueEdge>> removedHVEdges = removedHVEdgesBuilder.build();

    // TODO: can we get the type somehow?
    SymbolicValueFactory factory = SymbolicValueFactory.getInstance();
    // Value newUnknownValue =  factory.asConstant(factory.newIdentifier(null), null);
    // This is technically not good. We should use ConstantSymbolicExpression but we don't know the
    // type
    Value newUnknownValue = factory.newIdentifier(null);
    SMGValueAndSMGState newSMGValueAndState = this.copyAndAddValue(newUnknownValue);
    SMGState currentState = newSMGValueAndState.getSMGState();
    for (Entry<SMGObject, Set<SMGHasValueEdge>> objAndEdges : removedHVEdges.entrySet()) {
      SMGObject object = objAndEdges.getKey();
      for (SMGHasValueEdge hVEdge : objAndEdges.getValue()) {
        currentState =
            currentState.copyAndReplaceMemoryModel(
                currentState
                    .getMemoryModel()
                    .replaceValueAtWithAndCopy(
                        object,
                        hVEdge.getOffset(),
                        hVEdge.getSizeInBits(),
                        new SMGHasValueEdge(
                            newSMGValueAndState.getSMGValue(),
                            hVEdge.getOffset(),
                            hVEdge.getSizeInBits())));
      }
    }

    return new StateAndInfo<>(currentState, new SMGInformation(removedHVEdges));
  }

  public SMGState copyAndRemember(SMGInformation pForgottenInformation) {
    SMGState currentState = this;
    Value valueAgainIntroduced = null;
    for (Entry<SMGObject, Set<SMGHasValueEdge>> entry :
        pForgottenInformation.getHeapValuesPerObjectMap().entrySet()) {
      SMGObject object = entry.getKey();

      for (SMGHasValueEdge edgeToInsert : entry.getValue()) {
        valueAgainIntroduced =
            currentState.memoryModel.getValueFromSMGValue(edgeToInsert.hasValue()).orElseThrow();
        currentState =
            currentState.copyAndReplaceMemoryModel(
                currentState.memoryModel.replaceValueAtWithAndCopy(
                    object, edgeToInsert.getOffset(), edgeToInsert.getSizeInBits(), edgeToInsert));
      }
    }

    return currentState.copyAndReplaceMemoryModel(
        currentState.memoryModel.copyAndAddToheapValueWhitelist(valueAgainIntroduced));
  }

  @Override
  public StateAndInfo<SMGState, SMGInformation> copyAndForget(MemoryLocation pLocation) {
    String qualifiedName = pLocation.getQualifiedName();
    BigInteger offsetInBits = BigInteger.valueOf(pLocation.getOffset());
    SMGObject memory;
    if (qualifiedName.contains("::__retval__")) {
      // Return obj
      memory = getReturnObjectForMemoryLocation(pLocation);
    } else {
      // This is expected to succeed for global and local vars
      memory = getMemoryModel().getObjectForVariable(qualifiedName).orElseThrow();
    }

    SMGHasValueEdge edgeToRemove =
        memoryModel
            .getSmg()
            .getHasValueEdgeByPredicate(memory, o -> o.getOffset().compareTo(offsetInBits) == 0)
            .orElseThrow();

    Value removedValue = memoryModel.getValueFromSMGValue(edgeToRemove.hasValue()).orElseThrow();

    SymbolicProgramConfiguration newSPC =
        memoryModel.copyAndRemoveHasValueEdges(memory, ImmutableList.of(edgeToRemove));
    // We don't need to remove the entire variable! We just need to return unknown for it, which is
    // fulfilled by removing the value edge.
    SMGState newState = this.copyAndReplaceMemoryModel(newSPC);

    return new StateAndInfo<>(
        newState,
        new SMGInformation(
            PathCopyingPersistentTreeMap.<MemoryLocation, ValueAndValueSize>of()
                .putAndCopy(
                    pLocation, ValueAndValueSize.of(removedValue, edgeToRemove.getSizeInBits())),
            getMemoryModel().getSizeObMemoryForSPCWithoutHeap(),
            memoryModel.getVariableTypeMap(),
            memoryModel.getFunctionDeclarationsFromStackFrames()));
  }

  private SMGObject getReturnObjectForMemoryLocation(MemoryLocation memLoc) {
    String funcName = memLoc.getFunctionName();
    for (StackFrame stack : memoryModel.getStackFrames()) {
      if (stack.getFunctionDefinition().getQualifiedName().equals(funcName)) {
        return stack.getReturnObject().orElseThrow();
      }
    }
    // I can't throw a good exception because of the ValueAnalysis interface, forgive me
    return null;
  }

  @Override
  public SMGState copyAndRemember(MemoryLocation pLocation, SMGInformation pForgottenInformation) {
    ValueAndValueSize valueAndSize = pForgottenInformation.getAssignments().get(pLocation);
    SMGState newState;
    try {
      newState =
          assignNonHeapConstant(
              pLocation,
              valueAndSize,
              pForgottenInformation.getSizeInformationForVariablesMap(),
              pForgottenInformation.getTypeOfVariablesMap());
    } catch (SMG2Exception e) {
      // Should never happen
      throw new RuntimeException(e);
    }
    return newState;
  }

  public int getNumberOfGlobalVariables() {
    return memoryModel.getGlobalVariableToSmgObjectMap().size();
  }

  public boolean hasStackFrameForFunctionDef(CFunctionDeclaration edgeToCheck) {
    for (StackFrame frame : memoryModel.getStackFrames()) {
      // Yes == !
      if (frame.getFunctionDefinition() == edgeToCheck) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();

    for (Entry<MemoryLocation, ValueAndValueSize> memLoc :
        memoryModel.getMemoryLocationsAndValuesForSPCWithoutHeap().entrySet()) {
      CType readType = memoryModel.getTypeOfVariable(memLoc.getKey());
      Value valueRead = memLoc.getValue().getValue();
      if (readType != null && doesRequireUnionFloatConversion(valueRead, readType)) {
        // Float conversion is limited to the Java float types at the moment.
        // Larger float types are almost always unknown
        valueRead = castValueForUnionFloatConversion(valueRead, readType);
      }
      builder.append(memLoc.getKey() + ": " + valueRead);
      builder.append("\n");
    }

    return builder.toString();
  }

  public SMGState abstractIntoDLL(SMGObject root, BigInteger nfo, BigInteger pfo)
      throws SMG2Exception {
    // Check that the next object exists, is valid, has the same size and the same value in head
    Optional<SMGObject> maybeNext = getValidNextSLL(root, nfo);
    if (maybeNext.isEmpty() || maybeNext.orElseThrow().equals(root)) {
      return this;
    }
    SMGObject nextObj = maybeNext.orElseThrow();
    // Values not equal, continue traverse
    if (!checkEqualValuesForTwoStatesWithExcemptions(
        root, nextObj, ImmutableList.of(nfo, pfo), this, this, ImmutableSet.of())) {
      return abstractIntoDLL(nextObj, nfo, pfo);
    }
    // If it does, create a new SLL with the correct stuff
    // Copy the edges from the next object to the SLL
    SMGDoublyLinkedListSegment newDLL;
    if (root.isSLL()) {
      // Something went wrong
      // TODO: log and decide what to do here (can this even happen?)
      return this;
    } else if (root instanceof SMGDoublyLinkedListSegment) {
      SMGDoublyLinkedListSegment oldDLL = (SMGDoublyLinkedListSegment) root;
      newDLL =
          new SMGDoublyLinkedListSegment(
              oldDLL.getNestingLevel(),
              oldDLL.getSize(),
              oldDLL.getOffset(),
              oldDLL.getHeadOffset(),
              oldDLL.getNextOffset(),
              oldDLL.getPrevOffset(),
              oldDLL.getMinLength() + 1);
    } else {
      // We assume that the head is either at 0 if the nfo is not, or right behind the nfo if it is
      // not at 0, or right behind the pfo if the pfo is right behind nfo
      BigInteger headOffset;
      if (nfo.compareTo(root.getOffset()) == 0 || pfo.compareTo(root.getOffset()) == 0) {
        // 0 is taken
        if (nfo.compareTo(root.getOffset().add(memoryModel.getSizeOfPointer())) == 0
            || pfo.compareTo(root.getOffset().add(memoryModel.getSizeOfPointer())) == 0) {
          headOffset =
              root.getOffset()
                  .add(memoryModel.getSizeOfPointer())
                  .add(memoryModel.getSizeOfPointer());
        } else {
          // The slot in between the 2 pointers
          headOffset = root.getOffset().add(memoryModel.getSizeOfPointer());
        }
      } else {
        headOffset = BigInteger.ZERO;
      }

      newDLL =
          new SMGDoublyLinkedListSegment(
              root.getNestingLevel(), root.getSize(), root.getOffset(), headOffset, nfo, pfo, 2);
    }
    SMGState currentState = this.copyAndAddObjectToHeap(newDLL);
    currentState = currentState.copyAllValuesFromObjToObj(nextObj, newDLL);
    // Write prev from root into the current prev
    SMGValueAndSMGState prevPointer =
        currentState.readSMGValue(root, pfo, memoryModel.getSizeOfPointer());
    currentState =
        prevPointer
            .getSMGState()
            .writeValue(newDLL, pfo, memoryModel.getSizeOfPointer(), prevPointer.getSMGValue());

    // Replace ALL pointers that previously pointed to the root or the next object to the SLL
    // This currently simply changes where the pointers point to, the values are the same
    // TODO: increment the nesting level of all of those by 1
    // Careful as to not introduce a loop! As root does point to next,
    currentState =
        currentState.copyAndReplaceMemoryModel(
            currentState.memoryModel.replaceAllPointersTowardsWith(nextObj, newDLL));
    currentState =
        currentState.copyAndReplaceMemoryModel(
            currentState.memoryModel.replaceAllPointersTowardsWith(root, newDLL));

    // Remove the 2 old objects and continue
    currentState =
        currentState.copyAndReplaceMemoryModel(
            currentState.memoryModel.copyAndRemoveObjectFromHeap(nextObj));
    currentState =
        currentState.copyAndReplaceMemoryModel(
            currentState.memoryModel.copyAndRemoveObjectFromHeap(root));
    return currentState.abstractIntoDLL(newDLL, nfo, pfo);
  }

  public SMGState abstractIntoSLL(SMGObject root, BigInteger nfo) throws SMG2Exception {
    // Check that the next object exists, is valid, has the same size and the same value in head
    Optional<SMGObject> maybeNext = getValidNextSLL(root, nfo);
    if (maybeNext.isEmpty() || maybeNext.orElseThrow().equals(root)) {
      return this;
    }
    SMGObject nextObj = maybeNext.orElseThrow();
    // Values not equal, continue traverse
    if (!checkEqualValuesForTwoStatesWithExcemptions(
        root, nextObj, ImmutableList.of(nfo), this, this, ImmutableSet.of())) {
      return abstractIntoSLL(nextObj, nfo);
    }
    // If it does, create a new SLL with the correct stuff
    // Copy the edges from the next object to the SLL
    SMGSinglyLinkedListSegment newSLL;
    if (root instanceof SMGDoublyLinkedListSegment) {
      // Something went wrong
      // TODO: log and decide what to do here (can this even happen?)
      return this;
    } else if (root instanceof SMGSinglyLinkedListSegment) {
      SMGSinglyLinkedListSegment oldSLL = (SMGSinglyLinkedListSegment) root;
      newSLL =
          new SMGSinglyLinkedListSegment(
              oldSLL.getNestingLevel(),
              oldSLL.getSize(),
              oldSLL.getOffset(),
              oldSLL.getHeadOffset(),
              nfo,
              oldSLL.getMinLength() + 1);
    } else {
      // We assume that the head is either at 0 if the nfo is not, or right behind the nfo if it is
      // not.
      // We don't care about it however
      BigInteger headOffset =
          nfo.compareTo(root.getOffset()) == 0
              ? nfo.add(memoryModel.getSizeOfPointer())
              : BigInteger.ZERO;
      newSLL =
          new SMGSinglyLinkedListSegment(
              root.getNestingLevel(), root.getSize(), root.getOffset(), headOffset, nfo, 2);
    }
    SMGState currentState = this.copyAndAddObjectToHeap(newSLL);
    currentState = currentState.copyAllValuesFromObjToObj(nextObj, newSLL);

    // Replace ALL pointers that previously pointed to the root or the next object to the SLL
    // This currently simply changes where the pointers point to, the values are the same
    // TODO: increment the nesting level of all of those by 1
    // Careful as to not introduce a loop! As root does point to next,
    currentState =
        currentState.copyAndReplaceMemoryModel(
            currentState.memoryModel.replaceAllPointersTowardsWith(nextObj, newSLL));
    currentState =
        currentState.copyAndReplaceMemoryModel(
            currentState.memoryModel.replaceAllPointersTowardsWith(root, newSLL));

    // Remove the 2 old objects and continue
    currentState =
        currentState.copyAndReplaceMemoryModel(
            currentState.memoryModel.copyAndRemoveObjectFromHeap(nextObj));
    currentState =
        currentState.copyAndReplaceMemoryModel(
            currentState.memoryModel.copyAndRemoveObjectFromHeap(root));
    return currentState.abstractIntoSLL(newSLL, nfo);
  }

  private Optional<SMGObject> getValidNextSLL(SMGObject root, BigInteger nfo) {
    SMGState currentState = this;
    SMGValueAndSMGState valueAndState =
        currentState.readSMGValue(root, nfo, memoryModel.getSizeOfPointer());
    SMGValue value = valueAndState.getSMGValue();
    if (!memoryModel.getSmg().isPointer(value)) {
      return Optional.empty();
    }
    SMGObject nextObject = memoryModel.getSmg().getPTEdge(value).orElseThrow().pointsTo();
    if (!memoryModel.getSmg().isValid(nextObject)
        || root.getSize().compareTo(nextObject.getSize()) != 0) {
      return Optional.empty();
    }
    // Same object size, same content expect for the pointers, its valid -> ok
    // We don't need the state as it would only change for unknown reads
    return Optional.of(nextObject);
  }

  /**
   * Tries to dereference the pointer given by the argument {@link Value}. Returns a empty Optional
   * if the dereference fails because the entered {@link Value} is not known as a pointer. This does
   * not check validity of the Value!
   *
   * @param pointer the {@link Value} to dereference.
   * @return Optional filled with the {@link SMGObjectAndOffset} of the target of the pointer. Empty
   *     if its not a pointer in the current {@link SymbolicProgramConfiguration}.
   * @throws SMG2Exception in case of critical errors in the materialization of abstract memory.
   */
  public Optional<SMGStateAndOptionalSMGObjectAndOffset> dereferencePointer(Value pointer)
      throws SMG2Exception {
    if (!memoryModel.isPointer(pointer)) {
      // Not known or not known as a pointer, return nothing
      return Optional.empty();
    }
    SMGState currentState = this;
    SMGValue smgValueAddress = memoryModel.getSMGValueFromValue(pointer).orElseThrow();
    SMGPointsToEdge ptEdge = memoryModel.getSmg().getPTEdge(smgValueAddress).orElseThrow();
    // Every DLL is also a SLL
    if (ptEdge.pointsTo() instanceof SMGSinglyLinkedListSegment) {
      SMGValueAndSMGState NewPointerValueAndState =
          materializeReturnPointerValueAndCopy(smgValueAddress);
      currentState = NewPointerValueAndState.getSMGState();
      ptEdge =
          currentState
              .memoryModel
              .getSmg()
              .getPTEdge(NewPointerValueAndState.getSMGValue())
              .orElseThrow();
      Preconditions.checkArgument(!(ptEdge.pointsTo() instanceof SMGSinglyLinkedListSegment));
    }
    return Optional.of(
        SMGStateAndOptionalSMGObjectAndOffset.of(
            ptEdge.pointsTo(), ptEdge.getOffset(), currentState));
  }

  public Optional<SMGStateAndOptionalSMGObjectAndOffset> dereferencePointerWithoutMaterilization(
      Value pointer) {
    if (!memoryModel.isPointer(pointer)) {
      // Not known or not known as a pointer, return nothing
      return Optional.empty();
    }
    SMGState currentState = this;
    SMGValue smgValueAddress = memoryModel.getSMGValueFromValue(pointer).orElseThrow();
    SMGPointsToEdge ptEdge = memoryModel.getSmg().getPTEdge(smgValueAddress).orElseThrow();
    return Optional.of(
        SMGStateAndOptionalSMGObjectAndOffset.of(
            ptEdge.pointsTo(), ptEdge.getOffset(), currentState));
  }

  /**
   * Takes the value leading to a pointer that points towards abstracted heap (lists). The
   * abstracted heap is then materialized into a concrete list segment and the new pointer is
   * returned. (Technically the old value is equal to the new. This might change in the future)
   *
   * @param valueToPointerToAbstractObject a SMGValue that has a points to edge leading to
   *     abstracted memory.
   * @return SMGValueAndSMGState with the pointer value to the concrete memory extracted.
   * @throws SMG2Exception in case of critical errors.
   */
  public SMGValueAndSMGState materializeReturnPointerValueAndCopy(
      SMGValue valueToPointerToAbstractObject) throws SMG2Exception {
    SMGPointsToEdge ptEdge =
        memoryModel.getSmg().getPTEdge(valueToPointerToAbstractObject).orElseThrow();
    SMGObject obj = ptEdge.pointsTo();
    if (obj.isZero() || !memoryModel.isObjectValid(obj)) {
      throw new SMG2Exception("");
    }
    // DLLs are also SLLs
    Preconditions.checkArgument(obj instanceof SMGSinglyLinkedListSegment);
    return materializer.handleMaterilisation(valueToPointerToAbstractObject, obj, this);
  }
}
