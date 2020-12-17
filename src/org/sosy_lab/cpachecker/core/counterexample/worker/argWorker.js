// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

/**
 * The ARG Worker. Contains the logic for creating a single or multi ARG graph.
 * Once the graph(s) is/are created they are returned to the main script.
 * Should ONLY be used if ARG data is available!
 **/
function argWorker_function() {
    /* d3 and dagre-d3 will be hard-coded here during production. During
       development, the first posted message will include the url for those
       scripts. */
    /* EXTERNAL_LIBS */
    var json, nodes, edges, errorPath, relevantNodes, relevantEdges, errorGraphMap;
    var graphSplitThreshold = 700;
    var graphMap = [], graphCounter = 0,
        simplifiedGraphMap, simplifiedGraphCounter = 0,
        reducedGraphMap, reducedGraphCounter = 0;
    self.addEventListener("message", function (m) {
      if (m.data.externalLibPaths !== undefined) {
        m.data.externalLibPaths.forEach(url => {
          self.importScripts(url);
        });
      } else if (m.data.json !== undefined) {
        json = JSON.parse(m.data.json);
        nodes = json.nodes;
        edges = json.edges;
        buildGraphsAndPrepareResults(nodes, edges, "default")
        if(json.relevantedges !== undefined && json.relevantnodes !== undefined){
                relevantEdges = json.relevantedges;
                relevantNodes = json.relevantnodes;
                simplifiedGraphMap = [];
                buildGraphsAndPrepareResults(relevantNodes, relevantEdges, "relevant");
        }
        if(json.reducededges !== undefined && json.reducednodes !== undefined){
              reducedEdges = json.reducededges;
              reducedNodes = json.reducednodes;
              reducedGraphMap = [];
              buildGraphsAndPrepareResults(reducedNodes, reducedEdges, "witness");
        }
      } else if (m.data.errorPath !== undefined) {
        errorPath = [];
        JSON.parse(m.data.errorPath).forEach(function (d) {
          if (d.argelem !== undefined) {
            errorPath.push(d.argelem);
          }
        });
      } else if (m.data.renderer !== undefined) {
        if (graphMap.length > 0) {
          self.postMessage({
            "graph": JSON.stringify(graphMap[0]),
            "id": graphCounter
          });
          graphMap.shift();
          graphCounter++;
        } else {
          self.postMessage({
            "status": "done"
          });
          if (simplifiedGraphMap.length > 0) {
                  self.postMessage({
                        "graph": JSON.stringify(simplifiedGraphMap[0]),
                        "id": simplifiedGraphCounter,
                        "simplifiedGraph" : true
                  });
                  simplifiedGraphMap.shift();
                  simplifiedGraphCounter++;
          }
          if (typeof reducedGraphMap !== 'undefined' && reducedGraphMap.length > 0) {
                self.postMessage({
                      "graph": JSON.stringify(reducedGraphMap[0]),
                      "id": reducedGraphCounter,
                      "reducedGraph" : true
                });
                reducedGraphMap.shift();
                reducedGraphCounter++;
          }
          if (errorPath !== undefined) {
            errorGraphMap = [];
            graphCounter = 0;
            prepareErrorGraph();
          }
        }
      } else if (m.data.errorGraph !== undefined) {
        if (errorGraphMap.length > 0) {
          self.postMessage({
            "graph": JSON.stringify(errorGraphMap[0]),
            "id": graphCounter,
            "errorGraph": true
          });
          errorGraphMap.shift();
          graphCounter++;
        }
      } else if (m.data.split !== undefined) {
        graphSplitThreshold = m.data.split;
        if (errorGraphMap !== undefined && errorGraphMap.length > 0) {
          errorGraphMap = [];
        }
        buildGraphsAndPrepareResults();
      }
    }, false);

    function buildGraphsAndPrepareResults(nodes, edges, graphLabel) {
      if (nodes.length > graphSplitThreshold) {
        buildMultipleGraphs(nodes, edges, graphLabel);
      } else {
        buildSingleGraph(nodes, edges, graphLabel);
      }
    }

    // After the initial ARG graph has been send to the master script, prepare ARG containing only error path
    function prepareErrorGraph() {
      var errorNodes = [],
        errorEdges = [];
      nodes.forEach(function (n) {
        if (errorPath.includes(n.index)) {
          errorNodes.push(n);
        }
      });
      edges.forEach(function (e) {
        if (errorPath.includes(e.source) && errorPath.includes(e.target)) {
          errorEdges.push(e);
        }
      });
      if (errorNodes.length > graphSplitThreshold) {
        buildMultipleErrorGraphs(errorNodes, errorEdges);
      } else {
        var g = createGraph();
        setGraphNodes(g, errorNodes);
        setGraphEdges(g, errorEdges, false);
        errorGraphMap.push(g);
      }
    }

    function buildSingleGraph(nodes, edges, graphLabel) {
      var g = createGraph();
      setGraphNodes(g, nodes);
      setGraphEdges(g, edges, false);
      if(graphLabel === "relevant"){
        simplifiedGraphMap.push(g);
      } else if (graphLabel === "witness") {
        reducedGraphMap.push(g);
      } else {
        graphMap.push(g);
      }
    }

    // Split the ARG graph honoring the split threshold
    function buildMultipleGraphs(nodes, edges, graphLabel) {
      nodes.sort(function (firstNode, secondNode) {
        return firstNode.index - secondNode.index;
      })
      var requiredGraphs = Math.ceil(nodes.length / graphSplitThreshold);
      var firstGraphBuild = false;
      var nodesPerGraph = [];
      for (var i = 1; i <= requiredGraphs; i++) {
        if (!firstGraphBuild) {
          nodesPerGraph = nodes.slice(0, graphSplitThreshold);
          firstGraphBuild = true;
        } else {
          if (nodes[graphSplitThreshold * i - 1] !== undefined) {
            nodesPerGraph = nodes.slice(graphSplitThreshold * (i - 1), graphSplitThreshold * i);
          } else {
            nodesPerGraph = nodes.slice(graphSplitThreshold * (i - 1));
          }
        }
        var graph = createGraph();
        if (graphLabel === "relevant") {
            simplifiedGraphMap.push(graph);
        } else if (graphLabel === "witness") {
          reducedGraphMap.push(graph);
        } else {
          graphMap.push(graph);
        }
        setGraphNodes(graph, nodesPerGraph);
        var nodesIndices = []
        nodesPerGraph.forEach(function (n) {
          nodesIndices.push(n.index);
        });
        var graphEdges = edges.filter(function (e) {
          if (nodesIndices.includes(e.source) && nodesIndices.includes(e.target)) {
            return e;
          }
        });
        setGraphEdges(graph, graphEdges, true);
      }
      buildCrossgraphEdges(edges, false);
    }

    // Split the ARG error graph honoring the split threshold
    function buildMultipleErrorGraphs(errorNodes, errorEdges) {
      errorNodes.sort(function (firstNode, secondNode) {
        return firstNode.index - secondNode.index;
      })
      var requiredGraphs = Math.ceil(errorNodes.length / graphSplitThreshold);
      var firstGraphBuild = false;
      var nodesPerGraph = [];
      for (var i = 1; i <= requiredGraphs; i++) {
        if (!firstGraphBuild) {
          nodesPerGraph = errorNodes.slice(0, graphSplitThreshold);
          firstGraphBuild = true;
        } else {
          if (nodes[graphSplitThreshold * i - 1] !== undefined) {
            nodesPerGraph = errorNodes.slice(graphSplitThreshold * (i - 1), graphSplitThreshold * i);
          } else {
            nodesPerGraph = errorNodes.slice(graphSplitThreshold * (i - 1));
          }
        }
        var graph = createGraph();
        errorGraphMap.push(graph);
        setGraphNodes(graph, nodesPerGraph);
        var nodesIndices = []
        nodesPerGraph.forEach(function (n) {
          nodesIndices.push(n.index);
        });
        var graphEdges = errorEdges.filter(function (e) {
          if (nodesIndices.includes(e.source) && nodesIndices.includes(e.target)) {
            return e;
          }
        });
        setGraphEdges(graph, graphEdges, true);
      }
      buildCrossgraphEdges(errorEdges, true);
    }

    // Handle graph connecting edges
    function buildCrossgraphEdges(edges, errorGraph) {
      edges.forEach(function (edge) {
        var sourceGraph, targetGraph;
        if (errorGraph) {
          sourceGraph = getGraphForErrorNode(edge.source);
          targetGraph = getGraphForErrorNode(edge.target);
          if (sourceGraph < targetGraph) {
            errorGraphMap[sourceGraph].setNode("" + edge.source + edge.target + sourceGraph, {
              label: "",
              class: "arg-dummy",
              id: "dummy-" + edge.target
            });
            errorGraphMap[sourceGraph].setEdge(edge.source, "" + edge.source + edge.target + sourceGraph, {
              label: edge.label,
              id: "arg-edge" + edge.source + edge.target,
              style: "stroke-dasharray: 5, 5;",
              class: edgeClassDecider(edge)
            });
            errorGraphMap[targetGraph].setNode("" + edge.target + edge.source + targetGraph, {
              label: "",
              class: "dummy"
            });
            errorGraphMap[targetGraph].setEdge("" + edge.target + edge.source + targetGraph, edge.target, {
              label: "",
              labelStyle: "font-size: 12px;",
              id: "arg-edge_" + edge.source + "-" + edge.target,
              style: "stroke-dasharray: 5, 5;",
              class: "arg-split-edge"
            });
          } else if (sourceGraph > targetGraph) {
            errorGraphMap[sourceGraph].setNode("" + edge.source + edge.target + sourceGraph, {
              label: "",
              class: "arg-dummy",
              id: "dummy-" + edge.target
            });
            errorGraphMap[sourceGraph].setEdge(edge.source, "" + edge.source + edge.target + sourceGraph, {
              label: edge.label,
              id: "arg-edge" + edge.source + edge.target,
              arrowhead: "undirected",
              style: "stroke-dasharray: 5, 5;",
              class: edgeClassDecider(edge)
            })
            errorGraphMap[targetGraph].setNode("" + edge.target + edge.source + targetGraph, {
              label: "",
              class: "dummy"
            });
            errorGraphMap[targetGraph].setEdge("" + edge.target + edge.source + targetGraph, edge.target, {
              label: "",
              labelStyle: "font-size: 12px;",
              id: "arg-edge_" + edge.source + "-" + edge.target,
              arrowhead: "undirected",
              style: "stroke-dasharray: 5, 5;",
              class: "arg-split-edge"
            });
          }
        } else {
          sourceGraph = getGraphForNode(edge.source);
          targetGraph = getGraphForNode(edge.target);
          if (sourceGraph < targetGraph) {
            graphMap[sourceGraph].setNode("" + edge.source + edge.target + sourceGraph, {
              label: "",
              class: "arg-dummy",
              id: "dummy-" + edge.target
            });
            graphMap[sourceGraph].setEdge(edge.source, "" + edge.source + edge.target + sourceGraph, {
              label: edge.label,
              id: "arg-edge" + edge.source + edge.target,
              style: "stroke-dasharray: 5, 5;",
              class: edgeClassDecider(edge)
            });
            graphMap[targetGraph].setNode("" + edge.target + edge.source + targetGraph, {
              label: "",
              class: "dummy"
            });
            graphMap[targetGraph].setEdge("" + edge.target + edge.source + targetGraph, edge.target, {
              label: "",
              labelStyle: "font-size: 12px;",
              id: "arg-edge_" + edge.source + "-" + edge.target,
              style: "stroke-dasharray: 5, 5;",
              class: "arg-split-edge"
            });
          } else if (sourceGraph > targetGraph) {
            graphMap[sourceGraph].setNode("" + edge.source + edge.target + sourceGraph, {
              label: "",
              class: "arg-dummy",
              id: "dummy-" + edge.target
            });
            graphMap[sourceGraph].setEdge(edge.source, "" + edge.source + edge.target + sourceGraph, {
              label: edge.label,
              id: "arg-edge" + edge.source + edge.target,
              arrowhead: "undirected",
              style: "stroke-dasharray: 5, 5;",
              class: edgeClassDecider(edge)
            })
            graphMap[targetGraph].setNode("" + edge.target + edge.source + targetGraph, {
              label: "",
              class: "dummy"
            });
            graphMap[targetGraph].setEdge("" + edge.target + edge.source + targetGraph, edge.target, {
              label: "",
              labelStyle: "font-size: 12px;",
              id: "arg-edge_" + edge.source + "-" + edge.target,
              arrowhead: "undirected",
              style: "stroke-dasharray: 5, 5;",
              class: "arg-split-edge"
            });
          }
        }
      });
    }

    // Return the graph in which the nodeNumber is present
    function getGraphForNode(nodeNumber) {
      return graphMap.findIndex(function (graph) {
        return graph.nodes().includes("" + nodeNumber);
      })
    }

    // Return the graph in which the nodeNumber is present for an error node
    function getGraphForErrorNode(nodeNumber) {
      return errorGraphMap.findIndex(function (graph) {
        return graph.nodes().includes("" + nodeNumber);
      })
    }

    // create and return a graph element with a set transition
    function createGraph() {
      var g = new dagreD3.graphlib.Graph().setGraph({}).setDefaultEdgeLabel(
        function () {
          return {};
        });
      return g;
    }

    // Set nodes for the graph contained in the json nodes
    function setGraphNodes(graph, nodesToSet) {
      nodesToSet.forEach(function (n) {
        if (n.type === "target" && errorPath !== undefined && !errorPath.includes(n.index)) {
          errorPath.push(n.index);
        }
        graph.setNode(n.index, {
          label: n.label,
          class: "arg-node " + n.type,
          id: nodeIdDecider(n)
        });
      });
    }

    function nodeIdDecider(node) {
      if (errorGraphMap === undefined)
        return "arg-node" + node.index;
      else
        return "arg-error-node" + node.index;
    }

    // Set the graph edges
    function setGraphEdges(graph, edgesToSet, multigraph) {
      edgesToSet.forEach(function (e) {
        if (!multigraph || (graph.nodes().includes("" + e.source) && graph.nodes().includes("" + e.target))) {
          graph.setEdge(e.source, e.target, {
            label: e.label,
            lineInterpolate: "basis",
            class: edgeClassDecider(e),
            id: "arg-edge" + e.source + e.target,
            weight: edgeWeightDecider(e)
          });
        }
      });
    }

    // Set class for passed edge
    function edgeClassDecider(edge) {
      if (errorPath !== undefined && errorPath.includes(edge.source) && errorPath.includes(edge.target)) {
        return "arg-edge error-edge";
      } else {
        return "arg-edge";
      }
    }

    // Decide the weight for the edges based on type
    function edgeWeightDecider(edge) {
      if (edge.type === "covered") return 0;
      return 1;
    }

  }
