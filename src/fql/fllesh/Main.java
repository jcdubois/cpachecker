package fql.fllesh;

import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import common.Pair;

import cmdline.CPAMain;
import cmdline.CPAchecker;
import cmdline.Cilly;
import cpa.common.LogManager;
import cpaplugin.CPAConfiguration;
import cpaplugin.MainCPAStatistics;
import fql.backend.pathmonitor.Automaton;
import fql.backend.query.QueryEvaluation;
import fql.backend.targetgraph.Node;
import fql.backend.targetgraph.TargetGraph;
import fql.backend.testgoals.CoverageSequence;
import fql.backend.testgoals.TestGoal;
import fql.frontend.ast.query.Query;
import fql.frontend.parser.FQLParser;

public class Main {

  private static final String mConfig = "-config";
  private static final String mPropertiesFile = "test/config/simpleMustMayAnalysis.properties";
  
  /**
   * @param pArguments
   * @throws Exception 
   */
  public static void main(String[] pArguments) throws Exception {
    assert(pArguments != null);
    assert(pArguments.length > 1);
    
    String[] lArguments = new String[3];
    
    lArguments[0] = mConfig;
    lArguments[1] = mPropertiesFile;
    
    // check cilly invariance of source file, i.e., is it changed when preprocessed by cilly?
    Cilly lCilly = new Cilly();
    
    String lSourceFileName = pArguments[1];
    
    if (!lCilly.isCillyInvariant(pArguments[1])) {
      File lCillyProcessedFile = lCilly.cillyfy(pArguments[1]);
      
      lSourceFileName = lCillyProcessedFile.getAbsolutePath();
      
      System.err.println("WARNING: Given source file is not CIL invariant ... did preprocessing!");
      
      //throw new IllegalArgumentException("Please preprocess your source file with cilly! See options in HowTo.txt!");
    }
    
    // set source file name
    lArguments[2] = lSourceFileName;
    
    CPAConfiguration lConfiguration = new CPAConfiguration(lArguments);
    
    // necessary for LogManager
    CPAMain.cpaConfig = lConfiguration;
    
    LogManager lLogManager = LogManager.getInstance();
      
    MainCPAStatistics lStatistics = new MainCPAStatistics();
    
    CPAchecker lCPAchecker = new CPAchecker(lConfiguration, lLogManager, lStatistics);
    
    Query lQuery = parseQuery(pArguments[0]);
    
    TargetGraph lTargetGraph = TargetGraph.createTargetGraphFromCFA(lCPAchecker.getMainFunction());
    
    Pair<CoverageSequence, Automaton> lQueryEvaluation = QueryEvaluation.evaluate(lQuery, lTargetGraph);
    
    Automaton lPassingMonitor = lQueryEvaluation.getSecond();
    
    List<Pair<Automaton, Set<? extends TestGoal>>> lTargetSequence = new LinkedList<Pair<Automaton, Set<? extends TestGoal>>>();
    
    CoverageSequence lCoverageSequence = lQueryEvaluation.getFirst();
    
    for (Pair<Automaton, Set<? extends TestGoal>> lPair : lCoverageSequence) {
      lTargetSequence.add(lPair);
    }
    
    lTargetSequence.add(new Pair<Automaton, Set<? extends TestGoal>>(lCoverageSequence.getFinalMonitor(), Collections.singleton(new Node(lCPAchecker.getMainFunction().getExitNode()))));
    
    TestGoalEnumeration.run(lTargetSequence, lPassingMonitor, null);
  }
  
  private static Query parseQuery(String pFQLQuery) throws Exception {
    FQLParser lParser = new FQLParser(pFQLQuery);
    
    Object pParseResult;
    
    try {
      pParseResult = lParser.parse().value;
    }
    catch (Exception e) {
      System.out.println(pFQLQuery);
      
      throw e;
    }
    
    assert(pParseResult instanceof Query);
    
    return (Query)pParseResult;
  }

}
