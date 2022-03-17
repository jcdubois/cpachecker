// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.TreeMultimap;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionSummaryEdge;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;

public abstract class CfaTransformer {

  public CfaTransformer from(CfaTransformer pTransformer, CfaTransformer... pTransformers) {

    checkNotNull(pTransformer);

    ImmutableList<CfaTransformer> transformers = ImmutableList.copyOf(pTransformers);

    return new CfaTransformer() {

      @Override
      public CFA transform(CfaNetwork pCfaNetwork, CfaMetadata pCfaMetadata, LogManager pLogger) {

        CFA transformedCfa = pTransformer.transform(pCfaNetwork, pCfaMetadata, pLogger);
        for (CfaTransformer transformer : transformers) {
          transformedCfa = transformer.transform(transformedCfa, pCfaMetadata, pLogger);
        }

        return transformedCfa;
      }
    };
  }

  protected final CFA createCfa(
      CfaNetwork pCfaNetwork,
      CfaMetadata pCfaMetadata,
      LogManager pLogger,
      ImmutableList<CfaProcessor> pCfaProcessors,
      NodeConverter pNodeConverter,
      EdgeConverter pEdgeConverter) {
    return new CfaCreator(pCfaProcessors, pCfaNetwork, pNodeConverter, pEdgeConverter)
        .createCfa(pCfaMetadata, pLogger);
  }

  public abstract CFA transform(
      CfaNetwork pCfaNetwork, CfaMetadata pCfaMetadata, LogManager pLogger);

  public final CFA transform(CFA pCfa, CfaMetadata pCfaMetadata, LogManager pLogger) {
    return transform(CfaNetwork.of(pCfa), pCfaMetadata, pLogger);
  }

  /**
   * Indicates whether functions are connected by super-edges (i.e., function call and return
   * edges).
   */
  public enum CfaConnectedness {

    /** Functions are independent and not connected by super-edges. */
    INDEPENDENT_FUNCTIONS,

    /** Functions are connected by super-edges. */
    SUPERGRAPH
  }

  // TODO: move CFA processor interfaces out of CfaTransformer
  /** Marker interface for CFA post processors. */
  public interface CfaProcessor {}

  public interface ModifyingIndependentFunctionPostProcessor extends CfaProcessor {

    MutableCFA process(MutableCFA pCfa, LogManager pLogger);
  }

  public interface ReadOnlyIndependentFunctionPostProcessor extends CfaProcessor {

    void process(MutableCFA pCfa, LogManager pLogger);
  }

  public interface SupergraphCreator extends CfaProcessor {

    MutableCFA process(MutableCFA pCfa, LogManager pLogger);
  }

  public interface ModifyingSupergraphPostProcessor extends CfaProcessor {

    MutableCFA process(MutableCFA pCfa, LogManager pLogger);
  }

  public interface ReadOnlySupergraphPostProcessor extends CfaProcessor {

    void process(MutableCFA pCfa, LogManager pLogger);
  }

  public interface Substitution {

    CFANode toSubstitute(CFANode pOriginalNode);

    CFAEdge toSubstitute(CFAEdge pOriginalEdge);
  }

  @FunctionalInterface
  protected interface NodeConverter {

    CFANode convertNode(CFANode pCfaNode, CfaNetwork pCfaNetwork, Substitution pSubstitution);
  }

  @FunctionalInterface
  protected interface EdgeConverter {

    @Nullable CFAEdge convertEdge(
        CFAEdge pCfaEdge,
        CfaNetwork pCfaNetwork,
        Substitution pSubstitution,
        CfaConnectedness pConnectedness);
  }

  // TODO: move out of CfaTransformer
  public static final class CfaMetadata {

    private final MachineModel machineModel;
    private final Language language;
    private final ImmutableList<Path> fileNames;
    private final FunctionEntryNode mainFunctionEntry;
    private final CfaConnectedness connectedness;

    public CfaMetadata(
        MachineModel pMachineModel,
        Language pLanguage,
        ImmutableList<Path> pFileNames,
        FunctionEntryNode pMainFunctionEntry,
        CfaConnectedness pConnectedness) {

      machineModel = checkNotNull(pMachineModel);
      language = checkNotNull(pLanguage);
      fileNames = checkNotNull(pFileNames);
      mainFunctionEntry = checkNotNull(pMainFunctionEntry);
      connectedness = checkNotNull(pConnectedness);
    }

    public MachineModel getMachineModel() {
      return machineModel;
    }

    public Language getLanguage() {
      return language;
    }

    public ImmutableList<Path> getFileNames() {
      return fileNames;
    }

    public FunctionEntryNode getMainFunctionEntry() {
      return mainFunctionEntry;
    }

    public CfaConnectedness getConnectedness() {
      return connectedness;
    }
  }

  private final class CfaCreator implements Substitution {

    private final ImmutableList<CfaProcessor> cfaProcessors;

    private final CfaNetwork cfaNetwork;

    private final NodeConverter nodeConverter;
    private final EdgeConverter edgeConverter;

    private final Map<CFANode, CFANode> oldNodeToNewNode;
    private final Map<CFAEdge, CFAEdge> oldEdgeToNewEdge;

    private CfaConnectedness connectedness;

    private CfaCreator(
        ImmutableList<CfaProcessor> pCfaProcessors,
        CfaNetwork pCfaNetwork,
        NodeConverter pNodeConverter,
        EdgeConverter pEdgeConverter) {

      cfaProcessors = pCfaProcessors;

      cfaNetwork = pCfaNetwork;

      nodeConverter = pNodeConverter;
      edgeConverter = pEdgeConverter;

      oldNodeToNewNode = new HashMap<>();
      oldEdgeToNewEdge = new HashMap<>();
    }

    @Override
    public CFANode toSubstitute(CFANode pOldNode) {

      @Nullable CFANode newNode = oldNodeToNewNode.get(pOldNode);
      if (newNode != null) {
        return newNode;
      }

      return nodeConverter.convertNode(pOldNode, cfaNetwork, this);
    }

    @Override
    public CFAEdge toSubstitute(CFAEdge pOldEdge) {

      @Nullable CFAEdge newEdge = oldEdgeToNewEdge.get(pOldEdge);
      if (newEdge != null) {
        return newEdge;
      }

      newEdge = edgeConverter.convertEdge(pOldEdge, cfaNetwork, this, connectedness);

      if (newEdge instanceof FunctionSummaryEdge) {
        FunctionSummaryEdge newSummaryEdge = (FunctionSummaryEdge) newEdge;
        newEdge.getPredecessor().addLeavingSummaryEdge(newSummaryEdge);
        newEdge.getSuccessor().addEnteringSummaryEdge(newSummaryEdge);
      } else {
        newEdge.getPredecessor().addLeavingEdge(newEdge);
        newEdge.getSuccessor().addEnteringEdge(newEdge);
      }

      return newEdge;
    }

    private MutableCFA createIndependentFunctionCfa(CfaMetadata pCfaMetadata) {

      CFANode oldMainEntryNode = pCfaMetadata.getMainFunctionEntry();

      NavigableMap<String, FunctionEntryNode> newFunctions = new TreeMap<>();
      TreeMultimap<String, CFANode> newNodes = TreeMultimap.create();

      for (CFANode oldNode : cfaNetwork.nodes()) {

        CFANode newNode = toSubstitute(oldNode);
        String functionName = newNode.getFunction().getQualifiedName();

        if (newNode instanceof FunctionEntryNode) {
          newFunctions.put(functionName, (FunctionEntryNode) newNode);
        }

        newNodes.put(functionName, newNode);
      }

      connectedness = CfaConnectedness.INDEPENDENT_FUNCTIONS;
      cfaNetwork.edges().forEach(this::toSubstitute);

      return new MutableCFA(
          pCfaMetadata.getMachineModel(),
          newFunctions,
          newNodes,
          (FunctionEntryNode) oldNodeToNewNode.get(oldMainEntryNode),
          pCfaMetadata.getFileNames(),
          pCfaMetadata.getLanguage());
    }

    /** Removes all placeholder edges that were inserted instead of function calls. */
    private void removePlaceholderEdges() {

      Iterator<Map.Entry<CFAEdge, CFAEdge>> oldEdgeToNewEdgeIterator =
          oldEdgeToNewEdge.entrySet().iterator();

      while (oldEdgeToNewEdgeIterator.hasNext()) {

        Map.Entry<CFAEdge, CFAEdge> entry = oldEdgeToNewEdgeIterator.next();
        CFAEdge oldEdge = entry.getKey();
        CFAEdge newEdge = entry.getValue();

        if (oldEdge instanceof FunctionSummaryEdge) {
          oldEdgeToNewEdgeIterator.remove();
          newEdge.getPredecessor().removeLeavingEdge(newEdge);
          newEdge.getSuccessor().removeEnteringEdge(newEdge);
        }
      }
    }

    private CFA createCfa(CfaMetadata pCfaMetadata, LogManager pLogger) {

      MutableCFA newMutableCfa = createIndependentFunctionCfa(pCfaMetadata);

      for (CfaProcessor cfaProcessor : cfaProcessors) {
        if (cfaProcessor instanceof ModifyingIndependentFunctionPostProcessor) {
          newMutableCfa =
              ((ModifyingIndependentFunctionPostProcessor) cfaProcessor)
                  .process(newMutableCfa, pLogger);
        }
      }

      for (CfaProcessor cfaProcessor : cfaProcessors) {
        if (cfaProcessor instanceof ReadOnlyIndependentFunctionPostProcessor) {
          ((ReadOnlyIndependentFunctionPostProcessor) cfaProcessor).process(newMutableCfa, pLogger);
        }
      }

      if (pCfaMetadata.getConnectedness() == CfaConnectedness.SUPERGRAPH) {

        boolean supergraphCreated = false;

        for (CfaProcessor cfaProcessor : cfaProcessors) {
          if (cfaProcessor instanceof SupergraphCreator) {
            newMutableCfa = ((SupergraphCreator) cfaProcessor).process(newMutableCfa, pLogger);
            supergraphCreated = true;
          }
        }

        if (!supergraphCreated) {

          removePlaceholderEdges();

          connectedness = CfaConnectedness.SUPERGRAPH;
          cfaNetwork.edges().forEach(this::toSubstitute);
        }
      }

      for (CfaProcessor cfaProcessor : cfaProcessors) {
        if (cfaProcessor instanceof ModifyingSupergraphPostProcessor) {
          newMutableCfa =
              ((ModifyingSupergraphPostProcessor) cfaProcessor).process(newMutableCfa, pLogger);
        }
      }

      for (CfaProcessor cfaProcessor : cfaProcessors) {
        if (cfaProcessor instanceof ReadOnlySupergraphPostProcessor) {
          ((ReadOnlySupergraphPostProcessor) cfaProcessor).process(newMutableCfa, pLogger);
        }
      }

      return newMutableCfa.makeImmutableCFA(newMutableCfa.getVarClassification());
    }
  }
}
