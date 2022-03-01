// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.components;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.configuration.TimeSpanOption;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.core.AnalysisDirection;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.algorithm.Algorithm;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.BlockNode;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.BlockOperatorDecomposer;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.BlockTree;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.CFADecomposer;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.GivenSizeDecomposer;
import org.sosy_lab.cpachecker.core.algorithm.components.decomposition.SingleBlockDecomposer;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.Connection;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.ConnectionProvider;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.UpdatedTypeMap;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.memory.InMemoryConnectionProvider;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.nio_network.NetworkConnectionProvider;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.classic_network.ClassicNetworkConnectionProvider;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.observer.ErrorMessageObserver;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.observer.FaultLocalizationMessageObserver;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.observer.MessageListener;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.observer.ResultMessageObserver;
import org.sosy_lab.cpachecker.core.algorithm.components.exchange.observer.StatusObserver;
import org.sosy_lab.cpachecker.core.algorithm.components.worker.AnalysisOptions;
import org.sosy_lab.cpachecker.core.algorithm.components.worker.ComponentsBuilder;
import org.sosy_lab.cpachecker.core.algorithm.components.worker.ComponentsBuilder.Components;
import org.sosy_lab.cpachecker.core.algorithm.components.worker.FaultLocalizationWorker;
import org.sosy_lab.cpachecker.core.algorithm.components.worker.RootWorker;
import org.sosy_lab.cpachecker.core.algorithm.components.worker.Worker;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.core.specification.Specification;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.util.predicates.pathformula.PathFormulaManagerImpl;
import org.sosy_lab.cpachecker.util.predicates.pathformula.SSAMap;
import org.sosy_lab.cpachecker.util.predicates.smt.Solver;
import org.sosy_lab.cpachecker.util.statistics.StatInt;
import org.sosy_lab.cpachecker.util.statistics.StatKind;
import org.sosy_lab.cpachecker.util.statistics.StatisticsWriter;

@Options(prefix = "components")
public class ComponentAnalysis implements Algorithm, StatisticsProvider, Statistics {

  private final Configuration configuration;
  private final LogManager logger;
  private final CFA cfa;
  private final ShutdownManager shutdownManager;
  private final Specification specification;
  private final AnalysisOptions options;

  private Collection<Statistics> statsCollection;

  private final StatInt numberWorkers = new StatInt(StatKind.MAX, "number of workers");

  @Option(description = "algorithm to decompose the CFA")
  private DecompositionType decompositionType = DecompositionType.BLOCK_OPERATOR;

  @Option(description = "how to send messages")
  private ConnectionType connectionType = ConnectionType.NETWORK_NIO_UNSTABLE;

  @Option(description = "which worker to use")
  private WorkerType workerType = WorkerType.DEFAULT;

  @Option(description = "desired number of BlockNodes")
  private int desiredNumberOfBlocks = 5;

  @Option(description = "maximal overall wall-time for parallel analysis")
  @TimeSpanOption(codeUnit = TimeUnit.MILLISECONDS, min = 0)
  private TimeSpan maxWallTime = TimeSpan.ofSeconds(15 * 60);

  @Option(description = "whether to use daemon threads for workers")
  private boolean daemon = true;

  @Option(description = "whether to spawn util worker")
  private boolean spawnUtilWorkers = true;

  private enum DecompositionType {
    BLOCK_OPERATOR,
    GIVEN_SIZE,
    SINGLE_BLOCK
  }

  private enum ConnectionType {
    NETWORK_NIO_UNSTABLE,
    NETWORK,
    IN_MEMORY
  }

  private enum WorkerType {
    DEFAULT,
    SMART,
    MONITORED,
    FAULT_LOCALIZATION
  }

  public ComponentAnalysis(
      Configuration pConfig,
      LogManager pLogger,
      CFA pCfa,
      ShutdownManager pShutdownManager,
      Specification pSpecification) throws InvalidConfigurationException {
    configuration = pConfig;
    configuration.inject(this);
    logger = pLogger;
    cfa = pCfa;
    shutdownManager = pShutdownManager;
    specification = pSpecification;
    options = new AnalysisOptions(configuration);
    checkConfig();
  }

  /**
   * Currently, fault localization worker require linear blocks
   *
   * @throws InvalidConfigurationException if configuration for block analysis is invalid
   */
  private void checkConfig() throws InvalidConfigurationException {
    if (workerType == WorkerType.FAULT_LOCALIZATION) {
      if (decompositionType != DecompositionType.BLOCK_OPERATOR) {
        throw new InvalidConfigurationException(
            FaultLocalizationWorker.class.getCanonicalName() + " needs decomposition with type "
                + DecompositionType.BLOCK_OPERATOR + " but got " + decompositionType);
      }
    } else {
      if (options.isFlPreconditionAlwaysTrue()) {
        throw new InvalidConfigurationException(
            "Unused option: faultLocalizationPreconditionAlwaysTrue. Fault localization is deactivated");
      }
    }
  }

  private CFADecomposer getDecomposer() throws InvalidConfigurationException {
    switch (decompositionType) {
      case BLOCK_OPERATOR:
        return new BlockOperatorDecomposer(configuration);
      case GIVEN_SIZE:
        return new GivenSizeDecomposer(new BlockOperatorDecomposer(configuration),
            desiredNumberOfBlocks);
      case SINGLE_BLOCK:
        return new SingleBlockDecomposer();
      default:
        throw new AssertionError("Unknown DecompositionType: " + decompositionType);
    }
  }

  private ComponentsBuilder analysisWorker(
      ComponentsBuilder pBuilder,
      BlockNode pNode,
      UpdatedTypeMap pMap)
      throws CPAException, IOException, InterruptedException, InvalidConfigurationException {
    switch (workerType) {
      case DEFAULT:
        return pBuilder.addAnalysisWorker(pNode, pMap, options);
      case SMART:
        return pBuilder.addSmartAnalysisWorker(pNode, pMap, options);
      case MONITORED:
        return pBuilder.addMonitoredAnalysisWorker(pNode, pMap, options);
      case FAULT_LOCALIZATION:
        return pBuilder.addFaultLocalizationWorker(pNode, pMap, options);
      default:
        throw new AssertionError("Unknown WorkerType: " + workerType);
    }
  }

  private Class<? extends ConnectionProvider<?>> getConnectionProvider() {
    switch (connectionType) {
      case NETWORK_NIO_UNSTABLE:
        return NetworkConnectionProvider.class;
      case IN_MEMORY:
        return InMemoryConnectionProvider.class;
      case NETWORK:
        return ClassicNetworkConnectionProvider.class;
      default:
        throw new AssertionError("Unknown ConnectionType " + connectionType);
    }
  }

  @Override
  public AlgorithmStatus run(ReachedSet reachedSet) throws CPAException, InterruptedException {
    logger.log(Level.INFO, "Starting block analysis...");
    MessageListener listener = new MessageListener();
    listener.register(new ResultMessageObserver(reachedSet));
    listener.register(new ErrorMessageObserver());
    listener.register(new StatusObserver());
    try {
      // create block tree and reduce to relevant parts
      CFADecomposer decomposer = getDecomposer();
      BlockTree tree = decomposer.cut(cfa);
      logger.logf(Level.INFO, "Decomposed CFA in %d blocks using the %s.",
          tree.getDistinctNodes().size(), decomposer.getClass().getCanonicalName());
      //drawBlockDot(tree);
      Collection<BlockNode> removed = tree.removeEmptyBlocks();
      if (!removed.isEmpty()) {
        logger.log(Level.INFO, "Removed " + removed.size() + " empty BlockNodes from the tree.");
      }
      if (tree.isEmpty()) {
        return AlgorithmStatus.SOUND_AND_PRECISE;
      }

      // create type map (maps variables to their type)
      SSAMap ssaMap = getTypeMap(tree);
      UpdatedTypeMap map = new UpdatedTypeMap(ssaMap);

      // create workers
      Collection<BlockNode> blocks = tree.getDistinctNodes();
      ComponentsBuilder builder =
          new ComponentsBuilder(logger, cfa, specification, configuration, shutdownManager);
      builder = builder.withConnectionType(getConnectionProvider())
          .createAdditionalConnections(1);
      for (BlockNode distinctNode : blocks) {
        if (distinctNode.isRoot()) {
          builder = builder.addRootWorker(distinctNode, options);
        } else {
          builder = analysisWorker(builder, distinctNode, map);
        }
      }
      builder = builder.addResultCollectorWorker(blocks, options);

      if (spawnUtilWorkers) {
        builder = builder.addTimeoutWorker(maxWallTime, options);
        builder = builder.addVisualizationWorker(tree, options);
      }

      Components components = builder.build();

      numberWorkers.setNextValue(components.getWorkers().size());

      // run workers
      for (Worker worker : components.getWorkers()) {
        if (worker instanceof RootWorker) {
          worker.collectStatistics(statsCollection);
        }
        Thread thread = new Thread(worker, worker.getId());
        thread.setDaemon(daemon);
        thread.start();
      }

      // listen to messages
      Connection mainThreadConnection = components.getAdditionalConnections().get(0);
      mainThreadConnection.collectStatistics(statsCollection);
      if (workerType == WorkerType.FAULT_LOCALIZATION) {
        listener.register(new FaultLocalizationMessageObserver(logger, mainThreadConnection));
      }

      // wait for result
      while (true) {
        // breaks if one observer wants to finish.
        if (listener.process(mainThreadConnection.read())) {
          break;
        }
      }

      // finish and shutdown
      listener.finish();
      for (Worker worker : components.getWorkers()) {
        worker.shutdown();
      }
      mainThreadConnection.close();

      return listener.getObserver(StatusObserver.class).getStatus();
    } catch (InvalidConfigurationException | IOException | InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException pE) {
      logger.log(Level.SEVERE, "Block analysis stopped due to ", pE);
      throw new CPAException("Component Analysis run into an error.", pE);
    } finally {
      logger.log(Level.INFO, "Block analysis finished.");
    }
  }

  private SSAMap getTypeMap(BlockTree pTree)
      throws InvalidConfigurationException, CPATransferException, InterruptedException {
    Solver solver = Solver.create(configuration, logger, shutdownManager.getNotifier());
    PathFormulaManagerImpl manager =
        new PathFormulaManagerImpl(
            solver.getFormulaManager(),
            configuration,
            logger,
            shutdownManager.getNotifier(),
            cfa,
            AnalysisDirection.FORWARD);
    return manager.makeFormulaForPath(
        pTree.getDistinctNodes().stream().flatMap(m -> m.getEdgesInBlock().stream()).collect(
            Collectors.toList())).getSsa();
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatisticsCollection) {
    statsCollection = pStatisticsCollection;
    pStatisticsCollection.add(this);
  }

  @Override
  public void printStatistics(
      PrintStream out, Result result, UnmodifiableReachedSet reached) {
    StatisticsWriter.writingStatisticsTo(out)
        .put(numberWorkers);
  }

  @Override
  public @Nullable String getName() {
    return "Block Statistics";
  }

}
