/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.graal.llvm;

import static com.oracle.svm.core.util.VMError.unimplemented;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.ArrayList;
import java.util.Arrays;

import org.graalvm.compiler.nodeinfo.Verbosity;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.bytecode.Bytecode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.nodes.ValueNode;
import jdk.vm.ci.code.CodeUtil;
import java.util.Map;
import java.util.Iterator;
import com.oracle.svm.hosted.image.DebugInfoProviderHelper;
import jdk.vm.ci.meta.Local;
import java.util.TreeMap;


import com.oracle.svm.hosted.image.sources.SourceManager;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugCloseable;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.TimerKey;
import org.graalvm.compiler.lir.asm.CompilationResultBuilderFactory;
import org.graalvm.compiler.lir.phases.LIRSuites;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.common.AddressLoweringPhase;
import org.graalvm.compiler.phases.util.Providers;

import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.llvm.lowering.LLVMAddressLowering;
import com.oracle.svm.core.graal.llvm.util.LLVMOptions;

import jdk.internal.org.objectweb.asm.tree.ParameterNode;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.CompilationRequest;
import jdk.vm.ci.code.CompiledCode;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.site.ConstantReference;
import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.VMConstant;
import org.graalvm.nativeimage.ImageSingletons;

import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.graph.NodeSourcePosition;

public class SubstrateLLVMBackend extends SubstrateBackend {
    private static final TimerKey EmitLLVM = DebugContext.timer("EmitLLVM").doc("Time spent generating LLVM from HIR.");
    private static final TimerKey BackEnd = DebugContext.timer("BackEnd").doc("Time spent in EmitLLVM and Populate.");
    //TODO: Avoid this lock if possible
    private ReentrantLock imageSingletonesLock = new ReentrantLock();
    //private static Local[] localVars = null;

    //private static HashMap<Integer, Node> idToNodeMap = new HashMap<Integer, Node>();
    private static boolean checkNode = false;


    public SubstrateLLVMBackend(Providers providers) {
        super(providers);
    }

    @Override
    public BasePhase<CoreProviders> newAddressLoweringPhase(CodeCacheProvider codeCache) {
        return new AddressLoweringPhase(new LLVMAddressLowering());
    }

    @Override
    public CompilationResult createJNITrampolineMethod(ResolvedJavaMethod method, CompilationIdentifier identifier,
                    RegisterValue threadArg, int threadIsolateOffset, RegisterValue methodIdArg, int methodObjEntryPointOffset) {

        CompilationResult result = new CompilationResult(identifier);
        result.setMethods(method, Collections.emptySet());

        LLVMGenerator generator = new LLVMGenerator(getProviders(), result, null, method, 0);
        generator.createJNITrampoline(threadArg, threadIsolateOffset, methodIdArg, methodObjEntryPointOffset);
        byte[] bitcode = generator.getBitcode();
        result.setTargetCode(bitcode, bitcode.length);

        return result;
    }

    @Override
    protected CompiledCode createCompiledCode(ResolvedJavaMethod method, CompilationRequest compilationRequest, CompilationResult compilationResult, boolean isDefault, OptionValues options) {
        throw unimplemented();
    }

    public static void printGraph(StructuredGraph graph) {
        StructuredGraph.ScheduleResult schedule = graph.getLastSchedule();
        Block[] scheduledBlocks = schedule.getCFG().getBlocks();
        for (Block block : scheduledBlocks) {
            for (Node node : schedule.getBlockToNodesMap().get(block)) {
                if (node instanceof FrameState ) {
                    Verbosity verbostiy = Verbosity.Debugger;
                    System.out.println("The frame state node is: " + node.toString(verbostiy));
                }
                NodeSourcePosition pos = node.getNodeSourcePosition();
                if (pos == null) {
                    System.out.println("Printing in doBlock \n" + "Graph: " + graph +"\n" 
                    + "Block:" + block + "\n" + "Node is " + node + " it DOES NOT have source location\n");
                }else {
                    System.out.println("Printing in doBlock \n" + "Graph: " + graph +"\n" 
                    + "Block:" + block + "\n" + "Node is " + node.toString(Verbosity.All) + "\n" + "pos: " + pos +"\n");
                }
                System.out.println("node is alive: " + node.isAlive());
                System.out.println("node is deleted: " + node.isDeleted());
                System.out.println("node predecessor is: " + node.predecessor());
                System.out.println("\n");
            }
        }
    }

    @Override
    @SuppressWarnings("try")
    public void emitBackEnd(StructuredGraph graph, Object stub, ResolvedJavaMethod installedCodeOwner, CompilationResult result, CompilationResultBuilderFactory factory,
                    RegisterConfig config, LIRSuites lirSuites) {
        DebugContext debug = graph.getDebug();
        try (DebugContext.Scope s = debug.scope("BackEnd", graph.getLastSchedule()); DebugCloseable a = BackEnd.start(debug)) {
            if (graph.toString().contains("ReadQueries.isEventRead")) {
                for (Node n : graph.getNodes()) {
                    if (n instanceof UnreachableBeginNode) {
                        UnreachableBeginNode unreachableNode = (UnreachableBeginNode)n;
                        //GraphUtil.killCFG(unreachableNode);
                    }
                }
            }
            //printGraph(graph);
            emitLLVM(graph, result);
            dumpDebugInfo(result, graph);
        } catch (Throwable e) {
            throw debug.handle(e);
        } finally {
            graph.checkCancellation();
        }
    }

    @SuppressWarnings("try")
    private void emitLLVM(StructuredGraph graph, CompilationResult result) {
        DebugContext debug = graph.getDebug();
        try (DebugContext.Scope ds = debug.scope("EmitLLVM"); DebugCloseable a = EmitLLVM.start(debug)) {
            assert !graph.hasValueProxies();

            ResolvedJavaMethod method = graph.method();
            LLVMGenerator generator = new LLVMGenerator(getProviders(), result, graph, method, LLVMOptions.IncludeLLVMDebugInfo.getValue());
            NodeLLVMBuilder nodeBuilder = newNodeLLVMBuilder(graph, generator);

            /* LLVM generation */
            imageSingletonesLock.lock();
            if (LLVMOptions.IncludeLLVMSourceDebugInfo.getValue()) {
                //imageSingletonesLock.lock();
                if (ImageSingletons.contains(SourceManager.class) == false) {
                    ImageSingletons.add(SourceManager.class, new SourceManager());
                }
                //imageSingletonesLock.unlock();
            }
            generate(nodeBuilder, graph);
            imageSingletonesLock.unlock();
            byte[] bitcode = generator.getBitcode();
            result.setTargetCode(bitcode, bitcode.length);

            try (DebugContext.Scope s = debug.scope("LIRStages", nodeBuilder, null, null)) {
                /* Dump LIR along with HIR (the LIR is looked up from context) */
                debug.dump(DebugContext.BASIC_LEVEL, graph.getLastSchedule(), "After LIR generation");
            } catch (Throwable e) {
                throw debug.handle(e);
            }
        } catch (Throwable e) {
            throw debug.handle(e);
        } finally {
            graph.checkCancellation();
        }
    }

    protected NodeLLVMBuilder newNodeLLVMBuilder(StructuredGraph graph, LLVMGenerator generator) {
        return new NodeLLVMBuilder(graph, generator, getRuntimeConfiguration());
    }

    
    private static int numberOfArgumentInMethod(TreeMap<Integer, ArrayList<ValueNode>> sortedMap) {
        Map.Entry<Integer, ArrayList<ValueNode>> firstEntry = null;
        Iterator<Map.Entry<Integer, ArrayList<ValueNode>>> iterator = 
            sortedMap.entrySet().iterator();
        if (iterator.hasNext()) {
            firstEntry = iterator.next();
        }
        int count = 0;
        if (firstEntry != null) {
            ArrayList<ValueNode> firstValue = firstEntry.getValue();
            for (int i = 0; i < firstValue.size(); i ++) {
                // arguments are assumed to be all nodes in the very first framestate which is the entry 
                // point of the function. At this point, all other local variables that are yet to be 
                // declared are pre-filled with null in the map
                if (firstValue.get(i) != null) {
                    count ++;
                    if (checkNode) {
                        System.out.println("parameter node is: " + firstValue.get(i));
                    }
                }
            }
            if (checkNode) {
                System.out.println("count is: " + count);
            }
        }

        return count;
    }


    private static ArrayList<ValueNode> compareLocalVarDelta(
        ArrayList<ValueNode> localVariablePrev, 
        ArrayList<ValueNode> localVariableCurr,
        int count,
        ArrayList<Boolean> processedSlot
    ) { 
        // skip init
        if (localVariablePrev.isEmpty()) {
            for (int i = 0; i < localVariableCurr.size(); i ++)
                processedSlot.add(false);
            return localVariablePrev;
        }

        // looks like Java only enables assert when -ea switch (enable assertion)
        // is toggled, the below lines are not functioning at all
        assert localVariablePrev.size() == localVariableCurr.size();
        assert localVariableCurr.size() == processedSlot.size();
        
        ArrayList<ValueNode> diffArray = new ArrayList<>();
        for (int i = 0; i < localVariablePrev.size(); i ++) {
            
            if (i > count && 
                localVariablePrev.get(i) != 
                localVariableCurr.get(i) &&
                processedSlot.get(i) == false
            ) {
                // this implies that 
                // we are NOT handling a function parameter
                // we are seeing a delta
                // we have NOT processed the node yet
                diffArray.add(localVariableCurr.get(i));
            } else if (
                i > count && 
                localVariableCurr.get(i) == null &&
                processedSlot.get(i) == true) 
            {
                // this implies that
                // we already handled this slot; however, its acquisition is
                // released as the variable's scope is ended. Reset the slot
                // to be free
                processedSlot.set(i, false);
            } else {
                diffArray.add(null);
            }
        }

        return diffArray;
    }


    private static void handleDiff(
            ArrayList<ValueNode> diffNode, 
            ArrayList<Boolean> processedSlot, 
            HashMap<ValueNode, String> valueNodeToVarNameMap,
            ArrayList<Local> localVars
    ) {
        for (int i = 0; i < diffNode.size(); i ++) {
            // skip variable that yet to be declared or dead as it exits its scope
            if (diffNode.get(i) == null) {
                continue;
            }

            // skip already processed variable, this condition means that the variable is assigned/used
            // another time in the source code. But we do not care about the uses, only the definition point
            if (processedSlot.get(i) == true) {
                continue;
            }

            // constant node does not represent any instruction in LLVM, we skip it until it is initialized
            // by some control flow logic such that we can map it in the LLVM IR (and being able to traversed
            // by the use-def chain)
            if (diffNode.get(i).toString().contains("Constant")) {
                continue;
            }

            
            // index based isn't working, require slot checking
            for (int j = 0; j < localVars.size(); j ++) {
                if (localVars.get(j).getSlot() == (i)) {
                    if (checkNode) {
                        System.out.println(
                            "The slot in diff array is: " + i + " " +
                            "The processed Node is: " + diffNode.get(i) + "\n" +
                            "Its name is " + localVars.get(j).getName() + "\n"
                        );
                    }
                    valueNodeToVarNameMap.put(diffNode.get(i), localVars.get(j).getName());
                    processedSlot.set(i, true);
                    // remove from the local variable table once we inserted to the map
                    // as this is inverse one to one mapped 
                    // As we are processing the node following the line number order
                    // the uses of the slot in the local variable table shall also follow such manner
                    // Once a varname is used, we delete from the localVar array as it won't be used 
                    // again due to its inverse one to one mapping nature
                    localVars.remove(j);
                    break;
                }
            }
        }
    }


    private static void printLocalVar(ArrayList<ValueNode> varArray, int i, int a) {
        if (!checkNode) return;
        if (varArray.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        String nl = CodeUtil.NEW_LINE;
        sb.append(a == 0 ? "locals: [" : "diffs: [");
        //ArrayList<ValueNode> varArray = lineNumberToVarArrayMap.get(i);
        for (int j = 0; j < varArray.size(); j++) {
            sb.append(j == 0 ? "" : ", ").append(varArray.get(j) == null ? "_" : varArray.get(j).toString(Verbosity.Id));
        }
        sb.append("]").append(nl);
        System.out.println("line number is: " + i + "\n and " + sb.toString() );
    }


    private static void varNameGenerate(
        StructuredGraph.ScheduleResult schedule,
        Block[] scheduledBlocks,
        NodeLLVMBuilder nodeBuilder,
        StructuredGraph graph
    ) {
        // allocating variables in the stack prevent data racing, although I have the lock guarding before
        // and after the invocation of this function (not sure when threads are spawned).
        HashMap<Integer, ArrayList<ValueNode>> lineNumberToVarArrayMap = 
            new HashMap<Integer, ArrayList<ValueNode>>();
        HashMap<ValueNode, String> valueNodeToVarNameMap = 
            new HashMap<ValueNode, String>();
        Local[] localVars = null;

        // get the local variable table of this method from the bytecode
        localVars = DebugInfoProviderHelper.getAllLocalVar(graph.method());
        if (localVars == null) {
            // this entire algorithm relies on the mapping of local variable table
            // if it does not exist, there's nothing we can do
            return;
        }
        for (Local localVar : localVars) {
            if (checkNode) {
                System.out.println("Local var is: " + localVar.getName() + " and its slot is: " + localVar.getSlot());
            }
        }
        // convert Java native array into the ArrayList for the convinence of deletion
        ArrayList<Local> localVariables = new ArrayList<>(Arrays.asList(localVars));

        // extract each snapshot of the frame state used in the method
        for (Block block : scheduledBlocks) {
            BlockMap<List<Node>> blockMap = schedule.getBlockToNodesMap();
            for (Node node : blockMap.get(block)) {
                if (node instanceof FrameState) {
                    FrameState fs = (FrameState) node;
                    ArrayList<ValueNode> localVariableArray = new ArrayList<>();
                    for (int i = 0; i < fs.localsSize(); i++) {
                        if (fs.localAt(i) == null ) {
                            localVariableArray.add(null);
                        } else {
                            localVariableArray.add(fs.localAt(i));
                        }
                    }
                    lineNumberToVarArrayMap.put(
                        fs.getCode().asStackTraceElement(fs.bci).getLineNumber() ,localVariableArray
                    );
                }
            }
        } 

        // sort a series of snapshot based on the line number
        TreeMap<Integer, ArrayList<ValueNode>> sortedMap = new TreeMap<>(lineNumberToVarArrayMap);

        // calculate argument, assume first frame state list all arguments as there is always a first
        // state at the entry of the function call
        // we do not care the delta of these arguments
        int count = numberOfArgumentInMethod(sortedMap);
        

        // compare each snapshot and derive the mapping between definition Node and the variable name
        ArrayList<ValueNode> varArrayPrev = new ArrayList<>();
        ArrayList<Boolean> processedSlot = new ArrayList<>();
        for (Integer i : sortedMap.keySet()) {
            printLocalVar(sortedMap.get(i), i,  0);

            // delta of neighbouring framestate snapshot, which shall be the newly declared variable
            // for us to do the mapping between definition node and the variable name
            ArrayList<ValueNode> diffArray = compareLocalVarDelta(
                varArrayPrev, sortedMap.get(i), count, processedSlot
            );

            printLocalVar(diffArray, i,  1);
            
            // assign the mapping between definition node and the variable name
            handleDiff(diffArray, processedSlot, valueNodeToVarNameMap, localVariables);
            
            varArrayPrev = sortedMap.get(i);
        }
        
        
        // assign the valueNodeToVarNameMap to the nodeBuilder such that when nodeBuilder is emitting
        // LLVM instruction using graalVM graph's node, it can attach the corresponding variable name
        // as the metadata to the instruction
        nodeBuilder.builder.valueNodeToVarNameMap = valueNodeToVarNameMap;

    } 

    private static void generate(NodeLLVMBuilder nodeBuilder, StructuredGraph graph) {
        StructuredGraph.ScheduleResult schedule = graph.getLastSchedule();
        Block[] scheduledBlocks = schedule.getCFG().getBlocks();

        // serve as a debugging utility
        if (graph.toString().contains("org.apache.hadoop.hdfs.server.namenode.TestFsck")) {
            checkNode = true;
        } else {
            checkNode = false;
        }

        // map defintion node with the variable name declared using a series of framestate snapshot
        varNameGenerate(schedule, scheduledBlocks, nodeBuilder, graph);

        for (Block b : scheduledBlocks) {
            nodeBuilder.doBlock(b, graph, schedule.getBlockToNodesMap());
        }

        nodeBuilder.builder.globalVarListPerBlock.clear();
        nodeBuilder.finish();
    }

    private static void dumpDebugInfo(CompilationResult compilationResult, StructuredGraph graph) {
        DebugContext debug = graph.getDebug();

        if (debug.isCountEnabled()) {
            List<DataPatch> ldp = compilationResult.getDataPatches();
            JavaKind[] kindValues = JavaKind.values();
            CounterKey[] dms = new CounterKey[kindValues.length];
            for (int i = 0; i < dms.length; i++) {
                dms[i] = DebugContext.counter("DataPatches-%s", kindValues[i]);
            }

            for (DataPatch dp : ldp) {
                JavaKind kind = JavaKind.Illegal;
                if (dp.reference instanceof ConstantReference) {
                    VMConstant constant = ((ConstantReference) dp.reference).getConstant();
                    if (constant instanceof JavaConstant) {
                        kind = ((JavaConstant) constant).getJavaKind();
                    }
                }
                dms[kind.ordinal()].add(debug, 1);
            }

            DebugContext.counter("CompilationResults").increment(debug);
            DebugContext.counter("InfopointsEmitted").add(debug, compilationResult.getInfopoints().size());
            DebugContext.counter("DataPatches").add(debug, ldp.size());
        }
        debug.dump(DebugContext.BASIC_LEVEL, compilationResult, "After code generation");
    }
}
