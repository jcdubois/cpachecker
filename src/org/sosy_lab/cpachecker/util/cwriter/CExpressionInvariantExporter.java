// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.cwriter;

import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.io.PathTemplate;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.AbstractCFAEdge;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionReturnEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionSummaryEdge;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.Solver;
import org.sosy_lab.cpachecker.util.predicates.weakening.InductiveWeakeningManager;
import org.sosy_lab.cpachecker.util.predicates.weakening.WeakeningOptions;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.SolverException;

@Options(prefix = "cinvariants")
public class CExpressionInvariantExporter {

  @Option(
      secure = true,
      description =
          "Attempt to simplify the invariant before " + "exporting [may be very expensive].")
  private boolean simplify = false;

  @Option(
      secure = true,
      name = "external.forLines",
      description =
          "Specify lines for which an invariant should be written to external file (specified with"
              + " option cinvariants.external.file).Lines are specified as comma separated list of"
              + " individual lines x and line ranges x-y. Use the empty list to export all"
              + " available invariants.")
  private String exportInvariantsForLines = "";

  @Option(
      secure = true,
      name = "external.file",
      description = "File name for exporting external invariants.")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path externalInvariantFile = null;

  private final PathTemplate prefix;

  private final FormulaManagerView fmgr;
  private final BooleanFormulaManager bfmgr;
  private final FormulaToCExpressionConverter formulaToCExpressionConverter;
  private final InductiveWeakeningManager inductiveWeakeningManager;
  private final LogManager logger;

  public CExpressionInvariantExporter(
      Configuration pConfiguration,
      LogManager pLogManager,
      ShutdownNotifier pShutdownNotifier,
      PathTemplate pPrefix)
      throws InvalidConfigurationException {
    pConfiguration.inject(this);
    logger = pLogManager;
    prefix = pPrefix;
    @SuppressWarnings("resource")
    Solver solver = Solver.create(pConfiguration, pLogManager, pShutdownNotifier);
    fmgr = solver.getFormulaManager();
    bfmgr = fmgr.getBooleanFormulaManager();
    formulaToCExpressionConverter = new FormulaToCExpressionConverter(fmgr);
    inductiveWeakeningManager =
        new InductiveWeakeningManager(
            new WeakeningOptions(pConfiguration), solver, pLogManager, pShutdownNotifier);
  }

  /**
   * Export invariants extracted from {@code pReachedSet} into the file specified by the options as
   * {@code __VERIFIER_assume()} calls, intermixed with the program source code.
   */
  public void exportInvariant(CFA pCfa, UnmodifiableReachedSet pReachedSet)
      throws IOException, InterruptedException, SolverException {

    for (Path program : pCfa.getFileNames()) {
      // Grab only the last component of the program filename.
      Path trimmedFilename = program.getFileName();
      if (trimmedFilename != null) {
        try (Writer output =
            IO.openOutputFile(
                prefix.getPath(trimmedFilename.toString()), Charset.defaultCharset())) {
          writeProgramWithInvariants(output, program, pReachedSet);
        }
      }
    }

    exportInvariantsForRequestedLinesToFile(pReachedSet, pCfa.getFileNames().isEmpty());
  }

  private void writeProgramWithInvariants(
      Appendable out, Path filename, UnmodifiableReachedSet pReachedSet)
      throws IOException, InterruptedException, SolverException {

    Map<Integer, BooleanFormula> reporting = getInvariantsForFile(pReachedSet, filename);

    int lineNo = 0;
    String line;
    try (BufferedReader reader = Files.newBufferedReader(filename)) {
      while ((line = reader.readLine()) != null) {
        Optional<String> invariant = getInvariantForLine(lineNo, reporting);
        if (invariant.isPresent()) {
          out.append("__VERIFIER_assume(").append(invariant.orElseThrow()).append(");\n");
        }
        out.append(line).append('\n');
        lineNo++;
      }
    }
  }

  private Optional<String> getInvariantForLine(int lineNo, Map<Integer, BooleanFormula> reporting)
      throws InterruptedException, SolverException {
    BooleanFormula formula = reporting.get(lineNo);
    if (formula == null) {
      return Optional.empty();
    }
    if (simplify) {
      formula = simplifyInvariant(formula);
    }
    return Optional.of(formulaToCExpressionConverter.formulaToCExpression(formula));
  }

  /** Return mapping from line numbers to states associated with the given line. */
  private Map<Integer, BooleanFormula> getInvariantsForFile(
      UnmodifiableReachedSet pReachedSet, Path filename) {

    // One formula per reported state.
    Multimap<Integer, BooleanFormula> byState = HashMultimap.create();

    for (AbstractState state : pReachedSet) {

      CFANode loc = AbstractStates.extractLocation(state);
      if (loc != null && loc.getNumEnteringEdges() > 0) {
        CFAEdge edge = loc.getEnteringEdge(0);
        FileLocation location = edge.getFileLocation();

        if (location.getFileName().equals(filename)) {
          BooleanFormula reported = AbstractStates.extractReportedFormulas(fmgr, state);
          if (!bfmgr.isTrue(reported)) {
            byState.put(location.getStartingLineInOrigin(), reported);
          }
        }
      }
    }
    return Maps.transformValues(byState.asMap(), invariants -> bfmgr.or(invariants));
  }

  private BooleanFormula simplifyInvariant(BooleanFormula pInvariant)
      throws InterruptedException, SolverException {
    return inductiveWeakeningManager.removeRedundancies(pInvariant);
  }

  private void exportInvariantsForRequestedLinesToFile(
      final UnmodifiableReachedSet pReachedSet, final boolean withoutPrefix)
      throws IOException, InterruptedException, SolverException {
    if (externalInvariantFile != null && exportInvariantsForLines != null) {
      try (Writer output = IO.openOutputFile(externalInvariantFile, Charset.defaultCharset())) {
        Set<Integer> requestedLines = new HashSet<>();

        // read line numbers from input
        int posRangeSeparator, max;
        for (String line :
            Splitter.on(',').trimResults().omitEmptyStrings().split(exportInvariantsForLines)) {
          try {
            posRangeSeparator = line.indexOf('-');
            if (0 < posRangeSeparator) {
              max = Integer.parseInt(line.substring(posRangeSeparator + 1));
              for (int i = Integer.parseInt(line); i <= max; i++) {
                requestedLines.add(i);
              }
            } else {
              requestedLines.add(Integer.parseInt(line));
            }
          } catch (NumberFormatException e) {
            logger.log(Level.WARNING, "could not parse line number " + line + ", skipping!");
          }
        }

        // export invariants
        for (Entry<String, BooleanFormula> mapEntry :
            extractInvariants(pReachedSet, requestedLines, withoutPrefix).entrySet()) {
          output.append('[');
          output.append(mapEntry.getKey());
          output.append(',');
          output.append(convertInvariantToSingleLineString(mapEntry.getValue()));
          output.append("]\n");
        }
      }
    }
  }

  private Map<String, BooleanFormula> extractInvariants(
      final UnmodifiableReachedSet pReachedSet,
      final Set<Integer> pRequestedLines,
      final boolean withoutPrefix) {
    final String BEFORE_TOKEN = "-";
    // final String AFTER_TOKEN = "+";

    // One formula per reported state.
    Multimap<String, BooleanFormula> byState = HashMultimap.create();
    int lineNumber;
    String sourceFileName;
    CFANode loc;
    CFAEdge edge;

    for (AbstractState state : pReachedSet) {
      loc = AbstractStates.extractLocation(state);

      if (loc != null && loc.getNumLeavingEdges() > 0) {
        edge = loc.getLeavingEdge(0);

        if (!(edge instanceof AbstractCFAEdge)
            || edge instanceof BlankEdge
            || edge instanceof FunctionReturnEdge
            || edge instanceof FunctionSummaryEdge
            || (edge instanceof ADeclarationEdge
                && ((ADeclarationEdge) edge).getDeclaration().isGlobal())) {
          continue;
        }

        lineNumber = edge.getFileLocation().getStartingLineInOrigin();
        sourceFileName = edge.getFileLocation().getNiceFileName() + ":";
        if (pRequestedLines.isEmpty() || pRequestedLines.contains(lineNumber)) {
          BooleanFormula reported = AbstractStates.extractReportedFormulas(fmgr, state);
          if (!bfmgr.isTrue(reported)) {
            byState.put(
                withoutPrefix
                    ? lineNumber + BEFORE_TOKEN
                    : sourceFileName + lineNumber + BEFORE_TOKEN,
                reported);
          }
        }
      }
    }
    return Maps.transformValues(byState.asMap(), invariants -> bfmgr.or(invariants));
  }

  private String convertInvariantToSingleLineString(BooleanFormula pInvariant)
      throws InterruptedException, SolverException {
    if (simplify) {
      pInvariant = simplifyInvariant(pInvariant);
    }
    return formulaToCExpressionConverter.formulaToCExpression(pInvariant).replaceAll("\n", " ");
  }
}
