// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cpa.smg2;

import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.Sets;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.sosy_lab.common.collect.PathCopyingPersistentTreeMap;
import org.sosy_lab.common.collect.PersistentMap;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.cpa.smg.util.PersistentSet;
import org.sosy_lab.cpachecker.cpa.smg.util.PersistentStack;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMGObjectAndOffset;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMGObjectsAndValues;
import org.sosy_lab.cpachecker.cpa.smg2.util.SMGValueAndSPC;
import org.sosy_lab.cpachecker.cpa.smg2.util.SPCAndSMGObjects;
import org.sosy_lab.cpachecker.cpa.smg2.util.ValueAndValueSize;
import org.sosy_lab.cpachecker.cpa.smg2.util.value.CValue;
import org.sosy_lab.cpachecker.cpa.smg2.util.value.ValueWrapper;
import org.sosy_lab.cpachecker.cpa.value.type.NumericValue;
import org.sosy_lab.cpachecker.cpa.value.type.Value;
import org.sosy_lab.cpachecker.util.smg.SMG;
import org.sosy_lab.cpachecker.util.smg.SMGProveNequality;
import org.sosy_lab.cpachecker.util.smg.graph.SMGHasValueEdge;
import org.sosy_lab.cpachecker.util.smg.graph.SMGObject;
import org.sosy_lab.cpachecker.util.smg.graph.SMGPointsToEdge;
import org.sosy_lab.cpachecker.util.smg.graph.SMGTargetSpecifier;
import org.sosy_lab.cpachecker.util.smg.graph.SMGValue;
import org.sosy_lab.cpachecker.util.smg.util.SMGandValue;
import org.sosy_lab.cpachecker.util.states.MemoryLocation;

/**
 * This class models the memory with its global/heap/stack variables. Its idea is that we handle
 * only SMG specific stuff here, and have already transformed all CPA and C (or other) specific
 * stuff. This class splits the values by types, the values in themselfs are value ranges or
 * addresses. Also, we handle (in)equality for the value analysis here, as this is more delicate
 * than just ==, <, > etc. see proveInequality() for more info. Variable ranges are needed for the
 * abstraction (join of SMGs) to succeed more often. The abstraction might however merge SMG objects
 * and value ranges making read and equality non trivial.
 */
public class SymbolicProgramConfiguration {

  /** The SMG modelling this memory image. */
  private final SMG smg;

  /** Mapping of all global variables to their SMGObjects. Use the value mapping to get values. */
  private final PersistentMap<String, SMGObject> globalVariableMapping;

  /* The stack of stackFrames.
   * (Each function call creates a new one that has to be popd once the function is returned/ends)
   */
  private final PersistentStack<StackFrame> stackVariableMapping;

  /* (SMG)Objects on the heap. */
  private final PersistentSet<SMGObject> heapObjects;

  /* Map of (SMG)Objects externaly allocated. The bool denotes validity, true = valid, false = invalid i.e. after free() */
  private PersistentMap<SMGObject, Boolean> externalObjectAllocation;

  /**
   * Maps the symbolic value ranges to their abstract SMG counterparts. (SMGs use only abstract, but
   * unique values. Such that a SMGValue with id 1 is always equal only with a SMGValue with id 1.
   * We need symbolic value ranges for the values analysis. Concrete values would ruin abstraction
   * capabilities. The only exception are addresses, hence why they are seperate) . IMportant: this
   * mapping is only part of the total mapping! You NEED to map the SMGValue using the mapping of
   * the SPC! TODO: use SymbolicRegionManager or smth like it in a changed implementation of the
   * value. TODO: map the SMGValues using the SPC mapping or decide on a new mapping idea
   */
  private final ImmutableBiMap<Equivalence.Wrapper<Value>, SMGValue> valueMapping;

  private static final ValueWrapper valueWrapper = new ValueWrapper();

  private SymbolicProgramConfiguration(
      SMG pSmg,
      PersistentMap<String, SMGObject> pGlobalVariableMapping,
      PersistentStack<StackFrame> pStackVariableMapping,
      PersistentSet<SMGObject> pHeapObjects,
      PersistentMap<SMGObject, Boolean> pExternalObjectAllocation,
      ImmutableBiMap<Equivalence.Wrapper<Value>, SMGValue> pValueMapping) {
    globalVariableMapping = pGlobalVariableMapping;
    stackVariableMapping = pStackVariableMapping;
    smg = pSmg;
    externalObjectAllocation = pExternalObjectAllocation;
    heapObjects = pHeapObjects;
    valueMapping = pValueMapping;
  }

  /**
   * Creates a new {@link SymbolicProgramConfiguration} out of the elements given.
   *
   * @param pSmg the {@link SMG} of this SPC.
   * @param pGlobalVariableMapping the global variable map as {@link PersistentMap} mapping {@link
   *     String} to {@link SMGObject}.
   * @param pStackVariableMapping the stack variable mappings as a {@link PersistentStack} of {@link
   *     StackFrame}s.
   * @param pHeapObjects the heap {@link SMGObject}s on a {@link PersistentStack}.
   * @param pExternalObjectAllocation externally allocated {@link SMGObject}s on a map that saves
   *     the validity of the object. False is invalid.
   * @param pValueMapping {@link BiMap} mapping the {@link Value}s and {@link SMGValue}s.
   * @return the newly created {@link SymbolicProgramConfiguration}.
   */
  public static SymbolicProgramConfiguration of(
      SMG pSmg,
      PersistentMap<String, SMGObject> pGlobalVariableMapping,
      PersistentStack<StackFrame> pStackVariableMapping,
      PersistentSet<SMGObject> pHeapObjects,
      PersistentMap<SMGObject, Boolean> pExternalObjectAllocation,
      ImmutableBiMap<Equivalence.Wrapper<Value>, SMGValue> pValueMapping) {
    return new SymbolicProgramConfiguration(
        pSmg,
        pGlobalVariableMapping,
        pStackVariableMapping,
        pHeapObjects,
        pExternalObjectAllocation,
        pValueMapping);
  }

  /**
   * Creates a new, empty {@link SymbolicProgramConfiguration} and returns it.
   *
   * @param sizeOfPtr the size of the pointers in this new SPC in bits as {@link BigInteger}.
   * @return The newly created {@link SymbolicProgramConfiguration}.
   */
  public static SymbolicProgramConfiguration of(BigInteger sizeOfPtr) {
    return new SymbolicProgramConfiguration(
        new SMG(sizeOfPtr),
        PathCopyingPersistentTreeMap.of(),
        PersistentStack.of(),
        PersistentSet.of(SMGObject.nullInstance()),
        PathCopyingPersistentTreeMap.of(),
        ImmutableBiMap.of(valueWrapper.wrap(new NumericValue(0)), SMGValue.zeroValue()));
  }

  /**
   * @return The global variable mapping in a {@link PersistentMap} from String to the {@link
   *     SMGObject}.
   */
  public PersistentMap<String, SMGObject> getGlobalVariableToSmgObjectMap() {
    return globalVariableMapping;
  }

  /** Returns the SMG that models the memory used in this {@link SymbolicProgramConfiguration}. */
  public SMG getSmg() {
    return smg;
  }

  /**
   * Returns the size of the pointer used for the SMG of this {@link SymbolicProgramConfiguration}.
   */
  public BigInteger getSizeOfPointer() {
    return smg.getSizeOfPointer();
  }

  /**
   * Tries to check for inequality of 2 {@link SMGValue}s used in the SMG of this {@link
   * SymbolicProgramConfiguration}. This does NOT check the (concrete) CValues of the entered
   * values, but only if they refer to the same memory location in the SMG or not! TODO: remove
   * CValues and replace by symbolic value ranges.
   *
   * @param pValue1 A {@link SMGValue} to be check for inequality with pValue2.
   * @param pValue2 A {@link SMGValue} to be check for inequality with pValue1.
   * @return True if the 2 {@link SMGValue}s are not equal, false if they are equal.
   */
  public boolean proveInequality(SMGValue pValue1, SMGValue pValue2) {
    // Can this be solved without creating a new SMGProveNequality every time?
    // TODO: Since we need to rework the values anyway, make a new class for this.
    SMGProveNequality nequality = new SMGProveNequality(smg);
    return nequality.proveInequality(pValue1, pValue2);
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and adds the mapping from the variable
   * identifier pVarName to the {@link SMGObject} pNewObject.
   *
   * @param pNewObject - The {@link SMGObject} that the variable is mapped to.
   * @param pVarName - The variable identifier that the {@link SMGObject} is mapped to.
   * @return The copied SPC with the global object added.
   */
  public SymbolicProgramConfiguration copyAndAddGlobalObject(
      SMGObject pNewObject, String pVarName) {
    return of(
        smg.copyAndAddObject(pNewObject),
        globalVariableMapping.putAndCopy(pVarName, pNewObject),
        stackVariableMapping,
        heapObjects,
        externalObjectAllocation,
        valueMapping);
  }

  /**
   * @return The stack of {@link StackFrame}s modeling the function stacks of this {@link
   *     SymbolicProgramConfiguration}.
   */
  public PersistentStack<StackFrame> getStackFrames() {
    return stackVariableMapping;
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and adds the stack object given with the
   * variable name given to it.
   *
   * @param pNewObject the new stack object.
   * @param pVarName the name of the added stack object.
   * @return Copy of this SPC with the stack variable mapping added.
   */
  public SymbolicProgramConfiguration copyAndAddStackObject(SMGObject pNewObject, String pVarName) {
    StackFrame currentFrame = stackVariableMapping.peek();
    PersistentStack<StackFrame> tmpStack = stackVariableMapping.popAndCopy();
    currentFrame = currentFrame.copyAndAddStackVariable(pVarName, pNewObject);
    return of(
        smg.copyAndAddObject(pNewObject),
        globalVariableMapping,
        tmpStack.pushAndCopy(currentFrame),
        heapObjects,
        externalObjectAllocation,
        valueMapping);
  }

  /**
   * Replaces the Value mapping of the oldValue <-> SMGValue to the newValueToBeAssigned for the
   * same SMGValue. (Represents a change of Value without a write operation)
   *
   * @param oldValue The {@link Value} currently mapped to a {@link SMGValue} that should be
   *     replaced.
   * @param newValueToBeAssigned the new {@link Value} that should replace the old Value.
   * @return a SPC with the mapping of the Value replaced with the new one.
   */
  public SymbolicProgramConfiguration copyAndReplaceValueMapping(
      Value oldValue, Value newValueToBeAssigned) {
    ImmutableBiMap.Builder<Equivalence.Wrapper<Value>, SMGValue> builder = ImmutableBiMap.builder();
    for (Entry<Equivalence.Wrapper<Value>, SMGValue> entry : valueMapping.entrySet()) {
      if (entry.getKey().equals(valueWrapper.wrap(oldValue))) {
        builder.put(valueWrapper.wrap(newValueToBeAssigned), entry.getValue());
      } else {
        builder.put(entry);
      }
    }
    return of(
        smg,
        globalVariableMapping,
        stackVariableMapping,
        heapObjects,
        externalObjectAllocation,
        builder.buildOrThrow());
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and adds a {@link StackFrame} based on the
   * entered model and function definition. More information on StackFrames can be found in the
   * Stackframe class.
   *
   * @param pFunctionDefinition - The {@link CFunctionDeclaration} that the {@link StackFrame} will
   *     be based upon.
   * @param model - The {@link MachineModel} the new {@link StackFrame} be based upon.
   * @return The SPC copy with the new {@link StackFrame}.
   */
  public SymbolicProgramConfiguration copyAndAddStackFrame(
      CFunctionDeclaration pFunctionDefinition, MachineModel model) {
    StackFrame newStackFrame = new StackFrame(pFunctionDefinition, model);
    Optional<SMGObject> returnObj = newStackFrame.getReturnObject();
    if (returnObj.isEmpty()) {
      return of(
          smg,
          globalVariableMapping,
          stackVariableMapping.pushAndCopy(newStackFrame),
          heapObjects,
          externalObjectAllocation,
          valueMapping);
    }
    return of(
        smg.copyAndAddObject(returnObj.orElseThrow()),
        globalVariableMapping,
        stackVariableMapping.pushAndCopy(newStackFrame),
        heapObjects,
        externalObjectAllocation,
        valueMapping);
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and removes the global variable given.
   *
   * @param pIdentifier - String identifier of the global variable to remove.
   * @return Copy of the SPC with the global variable removed.
   */
  public SymbolicProgramConfiguration copyAndRemoveGlobalVariable(String pIdentifier) {
    Optional<SMGObject> objToRemove = Optional.ofNullable(globalVariableMapping.get(pIdentifier));
    if (objToRemove.isEmpty()) {
      return this;
    }
    PersistentMap<String, SMGObject> newGlobalsMap =
        globalVariableMapping.removeAndCopy(pIdentifier);
    SMG newSmg = smg.copyAndInvalidateObject(objToRemove.orElseThrow());
    return of(
        newSmg,
        newGlobalsMap,
        stackVariableMapping,
        heapObjects,
        externalObjectAllocation,
        valueMapping);
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and removes the stack variable given.
   *
   * @param pIdentifier - String identifier for the variable to be removed.
   * @return Copy of the SPC with the variable removed.
   */
  public SymbolicProgramConfiguration copyAndRemoveStackVariable(String pIdentifier) {
    // If a stack variable becomes out of scope, there are not more than one frames which could
    // contain the variable
    if (stackVariableMapping.isEmpty()) {
      return this;
    }

    for (StackFrame frame : stackVariableMapping) {
      if (frame.containsVariable(pIdentifier)) {
        SMGObject objToRemove = frame.getVariable(pIdentifier);
        StackFrame newFrame = frame.copyAndRemoveVariable(pIdentifier);
        PersistentStack<StackFrame> newStack =
            stackVariableMapping.replace(f -> f == frame, newFrame);
        SMG newSmg = smg.copyAndInvalidateObject(objToRemove);
        return of(
            newSmg,
            globalVariableMapping,
            newStack,
            heapObjects,
            externalObjectAllocation,
            valueMapping);
    }
  }
    return this;
  }

  /**
   * Copy SPC and add a object to the heap. * With checks: throws {@link IllegalArgumentException}
   * when asked to add an object already present.
   *
   * @param pObject Object to add.
   */
  public SymbolicProgramConfiguration copyAndAddHeapObject(SMGObject pObject) {
    if (heapObjects.contains(pObject)) {
      throw new IllegalArgumentException("Heap object already in the SMG: [" + pObject + "]");
    }
    return of(
        smg.copyAndAddObject(pObject),
        globalVariableMapping,
        stackVariableMapping,
        heapObjects.addAndCopy(pObject),
        externalObjectAllocation,
        valueMapping);
  }

  /**
   * Checks if the entered {@link SMGObject} is part of the heap.
   *
   * @param pObject {@link SMGObject} to be checked.
   * @return true if the object is in the heap.
   */
  public boolean isHeapObject(SMGObject pObject) {
    return heapObjects.contains(pObject);
  }

  public PersistentSet<SMGObject> getHeapObjects() {
    return heapObjects;
  }

  /**
   * Remove a top stack frame from the SMG, along with all objects in it, and any edges leading
   * from/to it.
   *
   * <p>TODO: A test case with (invalid) passing of an address of a dropped frame object outside,
   * and working with them. For that, we should probably keep those as invalid, so we can spot such
   * bugs.
   */
  public SymbolicProgramConfiguration copyAndDropStackFrame() {
    StackFrame frame = stackVariableMapping.peek();
    PersistentStack<StackFrame> newStack = stackVariableMapping.popAndCopy();
    SMG newSmg = smg;
    for (SMGObject object : frame.getAllObjects()) {
      newSmg = smg.copyAndInvalidateObject(object);
    }
    return of(
        newSmg,
        globalVariableMapping,
        newStack,
        heapObjects,
        externalObjectAllocation,
        valueMapping);
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and returns a new SPC with all entered
   * unreachable SMGObjects pruned + a collection of the unreachable {@link SMGObject}s.
   *
   * @return The new SPC without the entered SMGObjects and the unreachable {@link SMGObject}s.
   */
  public SPCAndSMGObjects copyAndPruneUnreachable() {
    Collection<SMGObject> visibleObjects =
        FluentIterable.concat(
                globalVariableMapping.values(),
                FluentIterable.from(stackVariableMapping)
                    .transformAndConcat(stackFrame -> stackFrame.getAllObjects()))
            .toSet();
    SMGObjectsAndValues reachable = smg.collectReachableObjectsAndValues(visibleObjects);
    Set<SMGObject> unreachableObjects =
        new HashSet<>(Sets.difference(smg.getObjects(), reachable.getObjects()));
    Set<SMGValue> unreachableValues =
        new HashSet<>(Sets.difference(smg.getValues(), reachable.getValues()));
    // Remove 0 Value and object
    unreachableObjects =
        unreachableObjects.stream().filter(o -> !o.isZero()).collect(Collectors.toSet());
    unreachableValues =
        unreachableValues.stream().filter(v -> !v.isZero()).collect(Collectors.toSet());
    SMG newSmg =
        smg.copyAndRemoveObjects(unreachableObjects).copyAndRemoveValues(unreachableValues);
    // copy into return collection
    PersistentSet<SMGObject> newHeapObjects = heapObjects;
    for (SMGObject smgObject : unreachableObjects) {
      newHeapObjects = newHeapObjects.removeAndCopy(smgObject);
    }
    return SPCAndSMGObjects.of(
        of(
            newSmg,
            globalVariableMapping,
            stackVariableMapping,
            newHeapObjects,
            externalObjectAllocation,
            valueMapping),
        unreachableObjects);
  }

  /**
   * @return {@link SMGObject} reserved for the return value of the current StackFrame.
   */
  public Optional<SMGObject> getReturnObjectForCurrentStackFrame() {
    return stackVariableMapping.peek().getReturnObject();
  }

  /**
   * @return true if there is a return object for the current stack frame.
   */
  public boolean hasReturnObjectForCurrentStackFrame() {
    return stackVariableMapping.peek().getReturnObject().isPresent();
  }

  /**
   * Copies the {@link SymbolicProgramConfiguration} and puts the mapping for the cValue to the
   * smgValue (and vice versa) into the returned copy. Note: the value is not yet added to the SMG!
   * And if there is a mapping already present for a Value or SMGValue this will fail!
   *
   * @param cValue {@link CValue} that is mapped to the entered smgValue.
   * @param smgValue {@link SMGValue} that is mapped to the entered cValue.
   * @return A copy of this SPC with the value mapping added.
   */
  public SymbolicProgramConfiguration copyAndPutValue(Value cValue, SMGValue smgValue) {
    ImmutableBiMap.Builder<Equivalence.Wrapper<Value>, SMGValue> builder = ImmutableBiMap.builder();
    return of(
        smg,
        globalVariableMapping,
        stackVariableMapping,
        heapObjects,
        externalObjectAllocation,
        builder.putAll(valueMapping).put(valueWrapper.wrap(cValue), smgValue).buildOrThrow());
  }

  /**
   * Copies the {@link SymbolicProgramConfiguration} and sets the externaly allocated {@link
   * SMGObject} validity to false. If the entered {@link SMGObject} is not yet in the external
   * allocation map this will enter it!
   *
   * @param pObject the {@link SMGObject} that is externally allocated to be set to invalid.
   * @return A copy of this SPC with the validity of the external object changed.
   */
  public SymbolicProgramConfiguration copyAndInvalidateExternalAllocation(SMGObject pObject) {
    return of(
        smg,
        globalVariableMapping,
        stackVariableMapping,
        heapObjects,
        externalObjectAllocation.putAndCopy(pObject, false),
        valueMapping);
  }

  /**
   * Returns an {@link Optional} that may be empty if no {@link SMGValue} for the entered {@link
   * Value} exists in the value mapping. If a mapping exists, it returns the {@link SMGValue} for
   * the entered {@link Value} inside the Optional.
   *
   * @param cValue The {@link Value} you want the potential {@link SMGValue} for.
   * @return {@link Optional} that contains the {@link SMGValue} for the entered {@link Value} if it
   *     exists, empty else.
   */
  public Optional<SMGValue> getSMGValueFromValue(Value cValue) {
    // TODO: map the returned value using the SPC mapping!
    return Optional.ofNullable(valueMapping.get(valueWrapper.wrap(cValue)));
  }

  /**
   * Returns an {@link Optional} that may be empty if no {@link Value} for the entered {@link
   * SMGValue} exists in the value mapping. If a mapping exists, it returns the {@link Value} for
   * the entered {@link SMGValue} inside the Optional. Reverse method of getSMGValue();
   *
   * @param smgValue The {@link SMGValue} you want the potential {@link Value} for.
   * @return {@link Optional} that contains the {@link Value} for the entered {@link SMGValue} if it
   *     exists, empty else.
   */
  public Optional<Value> getValueFromSMGValue(SMGValue smgValue) {
    Wrapper<Value> gettet = valueMapping.inverse().get(smgValue);
    if (gettet == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(gettet.get());
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and creates a mapping of a {@link Value} to a
   * newly created {@link SMGValue}. This checks if there is a mapping already, and if there exists
   * a mapping the unchanged SPC will be returned.
   *
   * @param cValue The {@link Value} you want to create a new, symbolic {@link SMGValue} for and map
   *     them to each other.
   * @return The new SPC with the new {@link SMGValue} and the value mapping from the entered {@link
   *     Value} to the new {@link SMGValue}.
   */
  public SymbolicProgramConfiguration copyAndCreateValue(Value cValue) {
    if (valueMapping.containsKey(valueWrapper.wrap(cValue))) {
      return this;
    }
    return copyAndPutValue(cValue, SMGValue.of());
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and adds the {@link SMGObject} pObject to it
   * before returning the new SPC.
   *
   * @param pObject The {@link SMGObject} you want to add to the SPC.
   * @return The new SPC with the {@link SMGObject} added.
   */
  public SymbolicProgramConfiguration copyAndAddExternalObject(SMGObject pObject) {
    return of(
        smg,
        globalVariableMapping,
        stackVariableMapping,
        heapObjects,
        externalObjectAllocation.putAndCopy(pObject, true),
        valueMapping);
  }

  /**
   * Copies this {@link SymbolicProgramConfiguration} and replaces the SMG with a new one. Meant for
   * read/write operations. The SMG has to be a successor of the old one.
   *
   * @param pSMG the new {@link SMG} that replaces the old one but is an successor of the old one.
   * @return The new SPC with the new {@link SMG}.
   */
  private SymbolicProgramConfiguration copyAndReplaceSMG(SMG pSMG) {
    return of(
        pSMG,
        globalVariableMapping,
        stackVariableMapping,
        heapObjects,
        externalObjectAllocation,
        valueMapping);
  }

  /**
   * Adds a SMGObject to the list of known SMGObject, but nothing else.
   *
   * @param newObject the new {@link SMGObject}.
   * @return a copy of the SPC + the object added.
   */
  public SymbolicProgramConfiguration copyAndAddStackObject(SMGObject newObject) {
    return copyAndReplaceSMG(getSmg().copyAndAddObject(newObject));
  }

  /**
   * Tries to search for a variable that is currently visible in the current {@link StackFrame} and
   * in the global variables and returns the variable if found. If it is not found, the {@link
   * Optional} will be empty. Note: this returns the SMGObject in which the value for the variable
   * is written. Read with the correct type!
   *
   * @param pName Name of the variable you want to search for as a {@link String}.
   * @return {@link Optional} that contains the variable if found, but is empty if not found.
   */
  public Optional<SMGObject> getObjectForVisibleVariable(String pName) {

    // Only look in the current stack frame
    StackFrame currentFrame = stackVariableMapping.peek();
    if (currentFrame.containsVariable(pName)) {
      return Optional.of(currentFrame.getVariable(pName));
    }

    // Second check global
    if (globalVariableMapping.containsKey(pName)) {
      return Optional.of(globalVariableMapping.get(pName));
    }
    // no variable found
    return Optional.empty();
  }

  /* Only used to reconstruct the state after interpolation! */
  Optional<SMGObject> getObjectForVariable(String pName) {
    // globals
    if (globalVariableMapping.containsKey(pName)) {
      return Optional.of(globalVariableMapping.get(pName));
    }

    // all locals
    for (StackFrame frame : stackVariableMapping) {
      if (frame.containsVariable(pName)) {
        return Optional.of(frame.getVariable(pName));
      }
    }

    // no variable found
    return Optional.empty();
  }

  /**
   * Tries to search for a variable that is currently visible in the {@link StackFrame} above the
   * current one and in the global variables and returns the variable if found. If it is not found,
   * the {@link Optional} will be empty. Note: this returns the SMGObject in which the value for the
   * variable is written. Read with the correct type!
   *
   * @param pName Name of the variable you want to search for as a {@link String}.
   * @return {@link Optional} that contains the variable if found, but is empty if not found.
   */
  public Optional<SMGObject> getObjectForVisibleVariableFromPreviousStackframe(String pName) {

    // Only look in the current stack frame
    StackFrame currentFrame = stackVariableMapping.popAndCopy().peek();
    if (currentFrame.containsVariable(pName)) {
      return Optional.of(currentFrame.getVariable(pName));
    }

    // Second check global
    if (globalVariableMapping.containsKey(pName)) {
      return Optional.of(globalVariableMapping.get(pName));
    }
    // no variable found
    return Optional.empty();
  }

  /**
   * Returns <code>true</code> if the entered {@link SMGObject} is externally allocated. This does
   * not check the validity of the external object.
   *
   * @param pObject The {@link SMGObject} you want to know if its externally allocated or not.
   * @return <code>true</code> if the entered pObject is externally allocated, <code>false</code> if
   *     not.
   */
  public boolean isObjectExternallyAllocated(SMGObject pObject) {
    return externalObjectAllocation.containsKey(pObject);
  }

  /**
   * Checks if a smg object is valid in the current context.
   *
   * @param pObject - the object to be checked
   * @return smg.isValid(pObject)
   */
  public boolean isObjectValid(SMGObject pObject) {
    return smg.isValid(pObject);
  }

  /**
   * The exact read method as specified by the original SMG paper! My guess is that a lot of other
   * forms of read (more exact reads) are not possible once we use join/abstration.
   *
   * @param pObject the {@link SMGObject} read.
   * @param pFieldOffset {@link BigInteger} offset.
   * @param pSizeofInBits {@link BigInteger} sizeInBits.
   * @return {@link SMGValueAndSPC} tuple for the copy of the SPC with the value read and the {@link
   *     SMGValue} read from it.
   */
  public SMGValueAndSPC readValue(
      SMGObject pObject, BigInteger pFieldOffset, BigInteger pSizeofInBits) {
    SMGandValue newSMGAndValue = smg.readValue(pObject, pFieldOffset, pSizeofInBits);
    return SMGValueAndSPC.of(newSMGAndValue.getValue(), copyAndReplaceSMG(newSMGAndValue.getSMG()));
  }

  /**
   * Copy SPC and add a pointer to an object at a specified offset. The target needs to be a region
   * (not a LIST)! If the mapping Value <-> SMGValue does not exists it is created, else the old
   * SMGValue is used. If there was a pointer from this SMGValue to an SMGObject it is replaced with
   * the one given.
   *
   * @param address the {@link Value} representing the address to the {@link SMGObject} at the
   *     specified offset.
   * @param target the {@link SMGObject} the {@link Value} points to.
   * @param offsetInBits the offset in the {@link SMGObject} in bits as {@link BigInteger}.
   * @return a copy of the SPC with the pointer to the {@link SMGObject} and the specified offset
   *     added.
   */
  public SymbolicProgramConfiguration copyAndAddPointerFromAddressToRegion(
      Value address, SMGObject target, BigInteger offsetInBits) {
    // If there is no SMGValue for this address we create it, else we use the existing
    SymbolicProgramConfiguration spc = this.copyAndCreateValue(address);
    SMGValue smgAddress = spc.getSMGValueFromValue(address).orElseThrow();
    // Now we create a points-to-edge from this value to the target object at the
    // specified offset, overriding any existing from this value
    SMGPointsToEdge pointsToEdge =
        new SMGPointsToEdge(target, offsetInBits, SMGTargetSpecifier.IS_REGION);
    return spc.copyAndReplaceSMG(spc.getSmg().copyAndAddPTEdge(pointsToEdge, smgAddress));
  }

  /**
   * Checks if a {@link SMGPointsToEdge} exists for the entered target object and offset and returns
   * a {@link Optional} that is filled with the SMGValue leading to the points-to-edge, empty if
   * there is none. (This always assumes SMGTargetSpecifier.IS_REGION)
   *
   * @param target {@link SMGObject} that is the target of the points-to-edge.
   * @param offset {@link BigInteger} offset in bits in the target.
   * @return either an empty {@link Optional} if there is no such edge, but the {@link SMGValue}
   *     within if there is such a points-to-edge.
   */
  public Optional<SMGValue> getAddressValueForPointsToTarget(SMGObject target, BigInteger offset) {
    BiMap<SMGValue, SMGPointsToEdge> pteMapping = getSmg().getPTEdgeMapping();
    SMGPointsToEdge searchedForEdge =
        new SMGPointsToEdge(target, offset, SMGTargetSpecifier.IS_REGION);

    for (Entry<SMGValue, SMGPointsToEdge> entry : pteMapping.entrySet()) {
      if (entry.getValue().compareTo(searchedForEdge) == 0) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  /**
   * Tries to dereference the pointer given by the argument {@link Value}. Returns a empty Optional
   * if the dereference fails because the entered {@link Value} is not known as a pointer. This does
   * not check validity of the Value!
   *
   * @param pointer the {@link Value} to dereference.
   * @return Optional filled with the {@link SMGObjectAndOffset} of the target of the pointer. Empty
   *     if its not a pointer in the current {@link SymbolicProgramConfiguration}.
   */
  public Optional<SMGObjectAndOffset> dereferencePointer(Value pointer) {
    if (!isPointer(pointer)) {
      // Not known or not known as a pointer, return nothing
      return Optional.empty();
    }
    SMGPointsToEdge ptEdge =
        smg.getPTEdge(valueMapping.get(valueWrapper.wrap(pointer))).orElseThrow();
    return Optional.of(SMGObjectAndOffset.of(ptEdge.pointsTo(), ptEdge.getOffset()));
  }

  /**
   * Returns true if the value entered is a pointer in the current SPC. This checks for the
   * existence of a known mapping from Value to SMGValue to a SMGPointsToEdge.
   *
   * @param maybePointer {@link Value} that you want to check.
   * @return true is the entered {@link Value} is a address that points to a memory location. False
   *     else.
   */
  public boolean isPointer(Value maybePointer) {
    if (valueMapping.containsKey(valueWrapper.wrap(maybePointer))) {
      return smg.isPointer(valueMapping.get(valueWrapper.wrap(maybePointer)));
    }
    return false;
  }

  /**
   * Write value into the SMG at the specified offset in bits with the size given in bits. This
   * assumes that the SMGValue is already correctly mapped, but will insert it into the SMG.
   */
  public SymbolicProgramConfiguration writeValue(
      SMGObject pObject, BigInteger pFieldOffset, BigInteger pSizeofInBits, SMGValue pValue) {
    // Adding the value should be save here, if its already added no harm is done
    SMG newSMG = smg.copyAndAddValue(pValue);
    return copyAndReplaceSMG(newSMG.writeValue(pObject, pFieldOffset, pSizeofInBits, pValue));
  }

  /**
   * This assumes that the entered {@link SMGObject} is part of the SPC!
   *
   * @param pObject the {@link SMGObject} to invalidate.
   * @return a new SPC with the entered object invalidated.
   */
  public SymbolicProgramConfiguration invalidateSMGObject(SMGObject pObject) {
    Preconditions.checkArgument(smg.getObjects().contains(pObject));
    SymbolicProgramConfiguration newSPC = this;
    if (isObjectExternallyAllocated(pObject)) {
      newSPC = copyAndInvalidateExternalAllocation(pObject);
    }
    SMG newSMG = newSPC.getSmg().copyAndInvalidateObject(pObject);
    return newSPC.copyAndReplaceSMG(newSMG);
  }

  /**
   * Returns local and global variables as {@link MemoryLocation}s and their respective Values and
   * type sizes (SMGs allow reads from different types, just the size has to match). This does not
   * return any heap related variables. (no pointers)
   *
   * @return a mapping of global/local program variables to their values and sizes.
   */
  private PersistentMap<MemoryLocation, ValueAndValueSize>
      getMemoryLocationsAndValuesForSPCWithoutHeap() {
    PersistentMap<MemoryLocation, ValueAndValueSize> map = PathCopyingPersistentTreeMap.of();
    Map<String, BigInteger> variableNameToMemorySizeInBits = new HashMap<>();

    for (Entry<String, SMGObject> globalEntry : globalVariableMapping.entrySet()) {
      String qualifiedName = globalEntry.getKey();
      SMGObject memory = globalEntry.getValue();
      Preconditions.checkArgument(smg.isValid(memory));
      for (SMGHasValueEdge valueEdge : smg.getEdges(memory)) {
        MemoryLocation memLoc =
            MemoryLocation.fromQualifiedName(qualifiedName, valueEdge.getOffset().longValueExact());
        SMGValue smgValue = valueEdge.hasValue();
        BigInteger typeSize = valueEdge.getSizeInBits();
        Preconditions.checkArgument(valueMapping.containsValue(smgValue));
        Value value = valueMapping.inverse().get(smgValue).get();
        if (isPointer(value)) {
          continue;
        }
        variableNameToMemorySizeInBits.put(qualifiedName, memory.getSize());
        ValueAndValueSize valueAndValueSize = ValueAndValueSize.of(value, typeSize);
        map = map.putAndCopy(memLoc, valueAndValueSize);
      }
    }

    for (StackFrame stackframe : stackVariableMapping) {
      for (Entry<String, SMGObject> localVariable : stackframe.getVariables().entrySet()) {
        String qualifiedName = localVariable.getKey();
        SMGObject memory = localVariable.getValue();
        Preconditions.checkArgument(smg.isValid(memory));
        for (SMGHasValueEdge valueEdge : smg.getEdges(memory)) {
          MemoryLocation memLoc =
              MemoryLocation.fromQualifiedName(
                  qualifiedName, valueEdge.getOffset().longValueExact());
          SMGValue smgValue = valueEdge.hasValue();
          BigInteger typeSize = valueEdge.getSizeInBits();
          Preconditions.checkArgument(valueMapping.containsValue(smgValue));
          Value value = valueMapping.inverse().get(smgValue).get();
          if (isPointer(value)) {
            continue;
          }
          variableNameToMemorySizeInBits.put(qualifiedName, memory.getSize());
          ValueAndValueSize valueAndValueSize = ValueAndValueSize.of(value, typeSize);
          map = map.putAndCopy(memLoc, valueAndValueSize);
        }
      }
    }
    return map;
  }
}
