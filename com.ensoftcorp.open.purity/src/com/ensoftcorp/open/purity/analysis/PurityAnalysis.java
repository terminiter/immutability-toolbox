package com.ensoftcorp.open.purity.analysis;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;

import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.EdgeDirection;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.NodeDirection;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.log.Log;
import com.ensoftcorp.atlas.core.query.Q;
import com.ensoftcorp.atlas.core.script.Common;
import com.ensoftcorp.atlas.core.xcsg.XCSG;

/**
 * Implements an Atlas native implementation of the context-sensitive method 
 * purity and side-effect analysis proposed in:
 * Reference 1: ReIm & ReImInfer: Checking and Inference of Reference Immutability, OOPSLA 2012 
 * Reference 2: Method Purity and ReImInfer: Method Purity Inference for Java, FSE 2012
 * 
 * @author Ben Holland, Ganesh Santhanam
 */
public class PurityAnalysis {

	private static final String IMMUTABILITY_TYPES = "IMMUTABILITY_QUALIFIER";
	
	/**
	 * Encodes the immutability qualifications as types 
	 * 
	 * @author Ben Holland
	 */
	public static enum ImmutabilityTypes {
		// note that MUTABLE <: POLYREAD <: READONLY
		// <: denotes a subtype relationship
		// MUTABLE is a subtype of POLYREAD and POLYREAD is a subtype of READONLY
		// MUTABLE is the most specific type and READONLY is the most generic type
		MUTABLE("MUTABLE"), POLYREAD("POLYREAD"), READONLY("READONLY");
		
		private String name;
		
		private ImmutabilityTypes(String name){
			this.name = name;
		}
		
		@Override
		public String toString(){
			return name;
		}
		
		public static ImmutabilityTypes getImmutabilityType(String s){
			if(s.equals("MUTABLE")){
				return ImmutabilityTypes.MUTABLE;
			} else if(s.equals("POLYREAD")){
				return ImmutabilityTypes.POLYREAD;
			} else if(s.equals("READONLY")){
				return ImmutabilityTypes.READONLY;
			} else {
				return null;
			}
		}
		
		/**
		 * Viewpoint adaptation is a concept from Universe Types.
		 * 
		 * Specifically for fields, 
		 * context=? and declaration=readonly => readonly
		 * context=q and declaration=mutable => q
		 * context=q and declaration=polyread => q
		 * 
		 * @param context
		 * @param declared
		 * @return
		 */
		public static ImmutabilityTypes getAdaptedFieldViewpoint(ImmutabilityTypes context, ImmutabilityTypes declaration){
			// see https://github.com/SoftwareEngineeringToolDemos/FSE-2012-ReImInfer/blob/master/inference-framework/checker-framework/checkers/src/checkers/inference/reim/ReimChecker.java#L216
//			if(declaration == ImmutabilityTypes.READONLY){
//				// ? and READONLY = READONLY
//				return ImmutabilityTypes.READONLY;
//			} else if(declaration == ImmutabilityTypes.MUTABLE){
//				// q and MUTABLE = q
//				return context;
//			} else {
//				// declared must be ImmutabilityTypes.POLYREAD
//				// q and POLYREAD = q
//				return context;
//			}
			
			// see https://github.com/proganalysis/type-inference/blob/master/inference-framework/checker-framework/checkers/src/checkers/inference2/reim/ReimChecker.java#L272
			return getAdaptedMethodViewpoint(context, declaration);
		}
		
		/**
		 * Viewpoint adaptation is a concept from Universe Types.
		 * 
		 * Specifically, 
		 * context=? and declaration=readonly => readonly
		 * context=? and declaration=mutable => mutable
		 * context=q and declaration=polyread => q
		 * 
		 * @param context
		 * @param declared
		 * @return
		 */
		public static ImmutabilityTypes getAdaptedMethodViewpoint(ImmutabilityTypes context, ImmutabilityTypes declaration){
			if(declaration == ImmutabilityTypes.READONLY){
				// ? and READONLY = READONLY
				return ImmutabilityTypes.READONLY;
			} else if(declaration == ImmutabilityTypes.MUTABLE){
				// ? and MUTABLE = MUTABLE
				return ImmutabilityTypes.MUTABLE;
			} else {
				// declared must be ImmutabilityTypes.POLYREAD
				// q and POLYREAD = q
				return context;
			}
		}
	}
	
	public static double run(){
		long start = System.nanoTime();
		runAnalysis();
		long stop = System.nanoTime();
		return (stop-start)/1000.0/1000.0;
	}
	
	private static void runAnalysis(){
		
		Q parameters = Common.universe().nodesTaggedWithAny(XCSG.Parameter);
		Q masterReturns = Common.universe().nodesTaggedWithAny(XCSG.MasterReturn);
		Q instanceVariables = Common.universe().nodesTaggedWithAny(XCSG.InstanceVariable);
		Q thisNodes = Common.universe().nodesTaggedWithAll(XCSG.InstanceMethod).children().nodesTaggedWithAny(XCSG.Identity);
		
		// create default types on each tracked item
		// note local variables may also get tracked, but only if need be during the analysis
		for(GraphElement trackedItem : parameters.union(masterReturns, instanceVariables, thisNodes).eval().nodes()){
			getTypes(trackedItem);
		}
		
		TreeSet<GraphElement> worklist = new TreeSet<GraphElement>();

		// add all assignments to worklist
		Q assignments = Common.universe().nodesTaggedWithAny(XCSG.Assignment);
//		Q initializerAssignments = Common.universe().methods("<init>").contained().nodesTaggedWithAny(XCSG.Assignment);
		for(GraphElement assignment : assignments.eval().nodes()){
			worklist.add(assignment);
		}
		
		int iteration = 0;
		while(true){
			Log.info("Iteration: " + iteration++);
			boolean fixedPoint = true;
			// TODO: consider removing workItems that only have the mutable tag from future iterations...
			for(GraphElement workItem : worklist){
				boolean typesChanged = applyInferenceRules(workItem);
				if(typesChanged){
					fixedPoint = false;
				}
			}
			if(fixedPoint){
				break;
			}
		}
	}
	
	private static boolean applyInferenceRules(GraphElement workItem){
		Graph localDFGraph = Common.universe().edgesTaggedWithAny(XCSG.LocalDataFlow).eval();
		Graph interproceduralDFGraph = Common.universe().edgesTaggedWithAny(XCSG.InterproceduralDataFlow).eval();
		Graph instanceVariableAccessedGraph = Common.universe().edgesTaggedWithAny(XCSG.InstanceVariableAccessed).eval();
		Graph identityPassedToGraph = Common.universe().edgesTaggedWithAny(XCSG.IdentityPassedTo).eval();
		Graph containsGraph = Common.universe().edgesTaggedWithAny(XCSG.Contains).eval();
		
		boolean typesChanged = false;
		
		// consider incoming data flow edges
		// incoming edges represent a read relationship in an assignment
		GraphElement to = workItem;
		AtlasSet<GraphElement> inEdges = localDFGraph.edges(to, NodeDirection.IN);
		for(GraphElement edge : inEdges){
			GraphElement from = edge.getNode(EdgeDirection.FROM);

			boolean involvesField = false;
			
			// TWRITE
			if(to.taggedWith(XCSG.InstanceVariableAssignment)){
				// Type Rule 3 - TWRITE
				// let, x.f = y

				// Reference (y) -LocalDataFlow-> InstanceVariableAssignment (.f)
				GraphElement y = from;
				GraphElement instanceVariableAssignment = to; // (.f)
				
				// InstanceVariableAssignment (.f) -InterproceduralDataFlow-> InstanceVariable (f)
				GraphElement interproceduralEdgeToField = interproceduralDFGraph.edges(instanceVariableAssignment, NodeDirection.OUT).getFirst();
				GraphElement f = interproceduralEdgeToField.getNode(EdgeDirection.TO);
				
				// Reference (x) -InstanceVariableAccessed-> InstanceVariableAssignment (.f)
				GraphElement instanceVariableAccessedEdge = instanceVariableAccessedGraph.edges(instanceVariableAssignment, NodeDirection.IN).getFirst();
				GraphElement x = instanceVariableAccessedEdge.getNode(EdgeDirection.FROM);
				if(x.taggedWith(XCSG.InstanceVariableValue)){
					interproceduralEdgeToField = interproceduralDFGraph.edges(x, NodeDirection.IN).getFirst();
					x = interproceduralEdgeToField.getNode(EdgeDirection.FROM);
				}
				
				Log.info("TWRITE (x.f=y, x=" + x.getAttr(XCSG.name) + ", f=" + f.getAttr(XCSG.name) + ", y=" + y.getAttr(XCSG.name) + ")");
				if(handleFieldWrite(x, f, y)){
					typesChanged = true;
				}
				
				involvesField = true;
			}
			
			// TREAD
			if(from.taggedWith(XCSG.InstanceVariableValue)){
				// Type Rule 4 - TREAD
				// let, x = y.f
				
				GraphElement x = to;
				GraphElement instanceVariableValue = from; // (.f)
				
				// InstanceVariable (f) -InterproceduralDataFlow-> InstanceVariableValue (.f) 
				GraphElement interproceduralEdgeFromField = interproceduralDFGraph.edges(instanceVariableValue, NodeDirection.IN).getFirst();
				GraphElement f = interproceduralEdgeFromField.getNode(EdgeDirection.FROM);
				
				// Reference (y) -InstanceVariableAccessed-> InstanceVariableValue (.f)
				GraphElement instanceVariableAccessedEdge = instanceVariableAccessedGraph.edges(instanceVariableValue, NodeDirection.IN).getFirst();
				GraphElement y = instanceVariableAccessedEdge.getNode(EdgeDirection.FROM);
				if(y.taggedWith(XCSG.InstanceVariableValue)){
					interproceduralEdgeFromField = interproceduralDFGraph.edges(y, NodeDirection.IN).getFirst();
					y = interproceduralEdgeFromField.getNode(EdgeDirection.FROM);
				}
				
				Log.info("TREAD (x=y.f, x=" + x.getAttr(XCSG.name) + ", y=" + y.getAttr(XCSG.name) + ", f=" + f.getAttr(XCSG.name) + ")");
				if(handleFieldRead(x, y, f)){
					typesChanged = true;
				}
				
				involvesField = true;
			}
			
			// TCALL
			boolean involvesCallsite = false;
			if(from.taggedWith(XCSG.CallSite)){
				// Type Rule 5 - TCALL
				// let, x = y.m(z)

				GraphElement x = to;
				GraphElement callsite = from;
				
				// IdentityPass (.this) -IdentityPassedTo-> CallSite (m)
				GraphElement identityPassedToEdge = identityPassedToGraph.edges(callsite, NodeDirection.IN).getFirst();
				GraphElement identityPass = identityPassedToEdge.getNode(EdgeDirection.FROM);
				
				// Receiver (y) -LocalDataFlow-> IdentityPass (.this)
				GraphElement localDataFlowEdge = localDFGraph.edges(identityPass, NodeDirection.IN).getFirst();
				GraphElement y = localDataFlowEdge.getNode(EdgeDirection.FROM);
				
				// ReturnValue (ret) -InterproceduralDataFlow-> CallSite (m)
				GraphElement interproceduralDataFlowEdge = interproceduralDFGraph.edges(callsite, NodeDirection.IN).getFirst();
				GraphElement ret = interproceduralDataFlowEdge.getNode(EdgeDirection.FROM);
				
				// Method (method) -Contains-> ReturnValue (ret)
				GraphElement containsEdge = containsGraph.edges(ret, NodeDirection.IN).getFirst();
				GraphElement method = containsEdge.getNode(EdgeDirection.FROM);
				
				// Method (method) -Contains-> Identity
				GraphElement identity = Common.toQ(method).children().nodesTaggedWithAny(XCSG.Identity).eval().nodes().getFirst();
				
				// Method (method) -Contains-> Parameter (p1, p2, ...)
				AtlasSet<GraphElement> parameters = Common.universe().edgesTaggedWithAny(XCSG.Contains)
						.successors(Common.toQ(method)).nodesTaggedWithAny(XCSG.Parameter).eval().nodes();
				
				// ControlFlow -Contains-> CallSite
				// CallSite -Contains-> ParameterPassed (z1, z2, ...)
				AtlasSet<GraphElement> parametersPassed = Common.toQ(callsite).parent().children().nodesTaggedWithAny(XCSG.ParameterPass).eval().nodes();
				
				// ParameterPassed (z1, z2, ...) -InterproceduralDataFlow-> Parameter (p1, p2, ...)
				// such that z1-InterproceduralDataFlow->p1, z2-InterproceduralDataFlow->p2, ...
				AtlasSet<GraphElement> parametersPassedEdges = Common.universe().edgesTaggedWithAny(XCSG.InterproceduralDataFlow)
						.betweenStep(Common.toQ(parametersPassed), Common.toQ(parameters)).eval().edges();
				
				Log.info("TCALL (x=y.m(z), x=" + x.getAttr(XCSG.name) + ", y=" + y.getAttr(XCSG.name) + ", m=" + method.getAttr("##signature") + ")");
				if(handleCall(x, y, identity, ret, parametersPassedEdges)){
					typesChanged = true;
				}
				
				involvesCallsite = true;
			}
			
			// TASSIGN
			if(!involvesField && !involvesCallsite){
				GraphElement x = to;
				GraphElement y = from;
				Log.info("TASSIGN (x=y, x=" + x.getAttr(XCSG.name) + ", y=" + y.getAttr(XCSG.name) + ")");
				if(handleAssignment(x, y)){
					typesChanged = true;
				}
			}
		}
		
		return typesChanged;
	}

	/**
	 * Solves and satisfies constraints for Type Rule 2 - TASSIGN
	 * Let, x = y
	 * 
	 * @param x The reference being written to
	 * @param y The reference be read from
	 * @return
	 */
	private static boolean handleAssignment(GraphElement x, GraphElement y) {
		boolean typesChanged = false;
		Set<ImmutabilityTypes> xTypes = getTypes(x);
		Set<ImmutabilityTypes> yTypes = getTypes(y);
		
		// process s(x)
		Set<ImmutabilityTypes> xTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes xType : xTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes yType : yTypes){
				if(xType.compareTo(yType) >= 0){
					isSatisfied = true;
					break satisfied;
				}
			}
			if(!isSatisfied){
				xTypesToRemove.add(xType);
			}
		}
		if(removeTypes(x, xTypesToRemove)){
			typesChanged = true;
		}
		
		// process s(y)
		Set<ImmutabilityTypes> yTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes yType : yTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes xType : xTypes){
				if(xType.compareTo(yType) >= 0){
					isSatisfied = true;
					break satisfied;
				}
			}
			if(!isSatisfied){
				yTypesToRemove.add(yType);
			}
		}
		if(removeTypes(y, yTypesToRemove)){
			typesChanged = true;
		}
		return typesChanged;
	}
	
	/**
	 * Solves and satisfies constraints for Type Rule 3 - TWRITE
	 * Let, x.f = y
	 * 
	 * @param x The receiver object
	 * @param f The field of the receiver object being written to
	 * @param y The reference being read from
	 * @return Returns true if the graph element's ImmutabilityTypes have changed
	 */
	private static boolean handleFieldWrite(GraphElement x, GraphElement f, GraphElement y) {
		boolean typesChanged = false;
		Set<ImmutabilityTypes> yTypes = getTypes(y);
		Set<ImmutabilityTypes> fTypes = getTypes(f);
		// x must be mutable
		if(setTypes(x, ImmutabilityTypes.MUTABLE)){
			typesChanged = true;
		}
		ImmutabilityTypes xType = ImmutabilityTypes.MUTABLE;
		
		// if a field changes in an object then any container objects which contain
		// that field have also changed
		if(x.taggedWith(XCSG.Field)){
			for(GraphElement containerField : getContainerFields(x)){
				if(setTypes(containerField, ImmutabilityTypes.MUTABLE)){
					typesChanged = true;
				}
			}
		}

		// process s(y)
		Set<ImmutabilityTypes> yTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes yType : yTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes fType : fTypes){
				ImmutabilityTypes xAdaptedF = ImmutabilityTypes.getAdaptedFieldViewpoint(xType, fType);
				if(xAdaptedF.compareTo(yType) >= 0){
					isSatisfied = true;
					break satisfied;
				}
			}
			if(!isSatisfied){
				yTypesToRemove.add(yType);
			}
		}
		if(removeTypes(y, yTypesToRemove)){
			typesChanged = true;
		}
		
		// process s(f)
		Set<ImmutabilityTypes> fTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes fType : fTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes yType : yTypes){
				ImmutabilityTypes xAdaptedF = ImmutabilityTypes.getAdaptedFieldViewpoint(xType, fType);
				if(xAdaptedF.compareTo(yType) >= 0){
					isSatisfied = true;
					break satisfied;
				}
			}
			if(!isSatisfied){
				fTypesToRemove.add(fType);
			}
		}
		if(removeTypes(f, fTypesToRemove)){
			typesChanged = true;
		}
		return typesChanged;
	}
	
	/**
	 * Solves and satisfies constraints for Type Rule 4 - TREAD
	 * Let, x = y.f
	 * 
	 * @param x The reference being written to
	 * @param y The receiver object
	 * @param f The field of the receiver object being read from
	 * @return Returns true if the graph element's ImmutabilityTypes have changed
	 */
	private static boolean handleFieldRead(GraphElement x, GraphElement y, GraphElement f) {
		boolean typesChanged = false;
		Set<ImmutabilityTypes> fTypes = getTypes(f);
		Set<ImmutabilityTypes> xTypes = getTypes(x);
		Set<ImmutabilityTypes> yTypes = getTypes(y);
		
		// if x is only MUTABLE then the field and its container fields must be mutable as well
		if(xTypes.contains(ImmutabilityTypes.MUTABLE) && xTypes.size() == 1){
			for(GraphElement containerField : getContainerFields(f)){
				if(setTypes(containerField, ImmutabilityTypes.MUTABLE)){
					typesChanged = true;
				}
			}
		}
		
		// process s(x)
		Set<ImmutabilityTypes> xTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes xType : xTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes yType : yTypes){
				for(ImmutabilityTypes fType : fTypes){
					ImmutabilityTypes yAdaptedF = ImmutabilityTypes.getAdaptedFieldViewpoint(yType, fType);
					if(xType.compareTo(yAdaptedF) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
			}
			if(!isSatisfied){
				xTypesToRemove.add(xType);
			}
		}
		if(removeTypes(x, xTypesToRemove)){
			typesChanged = true;
		}
		
		// process s(y)
		Set<ImmutabilityTypes> yTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes yType : yTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes xType : xTypes){
				for(ImmutabilityTypes fType : fTypes){
					ImmutabilityTypes yAdaptedF = ImmutabilityTypes.getAdaptedFieldViewpoint(yType, fType);
					if(xType.compareTo(yAdaptedF) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
			}
			if(!isSatisfied){
				yTypesToRemove.add(yType);
			}
		}
		if(removeTypes(y, yTypesToRemove)){
			typesChanged = true;
		}
		
		// process s(f)
		Set<ImmutabilityTypes> fTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes fType : fTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes xType : xTypes){
				for(ImmutabilityTypes yType : yTypes){
					ImmutabilityTypes yAdaptedF = ImmutabilityTypes.getAdaptedFieldViewpoint(yType, fType);
					if(xType.compareTo(yAdaptedF) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
			}
			if(!isSatisfied){
				fTypesToRemove.add(fType);
			}
		}
		if(removeTypes(f, fTypesToRemove)){
			typesChanged = true;
		}
		
		return typesChanged;
	}
	
	private static boolean handleCall(GraphElement x, GraphElement y, GraphElement identity, GraphElement ret, AtlasSet<GraphElement> parametersPassedEdges) {
		boolean typesChanged = false;
		Set<ImmutabilityTypes> xTypes = getTypes(x);
		Set<ImmutabilityTypes> yTypes = getTypes(y);
		Set<ImmutabilityTypes> identityTypes = getTypes(identity);
		Set<ImmutabilityTypes> retTypes = getTypes(ret);
		
		/////////////////////// start qx adapt qret <: qx /////////////////////// 
		// process s(x)
		Set<ImmutabilityTypes> xTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes xType : xTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes retType : retTypes){
				ImmutabilityTypes xAdaptedRet = ImmutabilityTypes.getAdaptedMethodViewpoint(xType, retType);
				if(xType.compareTo(xAdaptedRet) >= 0){
					isSatisfied = true;
					break satisfied;
				}
			}
			if(!isSatisfied){
				xTypesToRemove.add(xType);
			}
		}
		if(removeTypes(x, xTypesToRemove)){
			typesChanged = true;
		}
		
		// process s(ret)
		Set<ImmutabilityTypes> retTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes retType : retTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes xType : xTypes){
				ImmutabilityTypes xAdaptedRet = ImmutabilityTypes.getAdaptedMethodViewpoint(xType, retType);
				if(xType.compareTo(xAdaptedRet) >= 0){
					isSatisfied = true;
					break satisfied;
				}
			}
			if(!isSatisfied){
				retTypesToRemove.add(retType);
			}
		}
		if(removeTypes(ret, retTypesToRemove)){
			typesChanged = true;
		}
		/////////////////////// end qx adapt qret <: qx /////////////////////// 
		
		/////////////////////// start qy <: qx adapt qthis /////////////////////// 
		
		// process s(y)
		Set<ImmutabilityTypes> yTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes yType : yTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes xType : xTypes){
				for(ImmutabilityTypes identityType : identityTypes){
					ImmutabilityTypes xAdaptedThis = ImmutabilityTypes.getAdaptedMethodViewpoint(xType, identityType);
					if(xAdaptedThis.compareTo(yType) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
			}
			if(!isSatisfied){
				yTypesToRemove.add(yType);
			}
		}
		if(removeTypes(y, yTypesToRemove)){
			typesChanged = true;
		}
		
		// process s(x)
		xTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes xType : xTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes yType : yTypes){
				for(ImmutabilityTypes identityType : identityTypes){
					ImmutabilityTypes xAdaptedThis = ImmutabilityTypes.getAdaptedMethodViewpoint(xType, identityType);
					if(xAdaptedThis.compareTo(yType) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
			}
			if(!isSatisfied){
				xTypesToRemove.add(xType);
			}
		}
		if(removeTypes(x, xTypesToRemove)){
			typesChanged = true;
		}
		
		// process s(identity)
		Set<ImmutabilityTypes> identityTypesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes identityType : identityTypes){
			boolean isSatisfied = false;
			satisfied:
			for(ImmutabilityTypes xType : xTypes){
				for(ImmutabilityTypes yType : yTypes){
					ImmutabilityTypes xAdaptedThis = ImmutabilityTypes.getAdaptedMethodViewpoint(xType, identityType);
					if(xAdaptedThis.compareTo(yType) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
			}
			if(!isSatisfied){
				identityTypesToRemove.add(identityType);
			}
		}
		if(removeTypes(identity, identityTypesToRemove)){
			typesChanged = true;
		}
		
		/////////////////////// end qy <: qx adapt qthis ///////////////////////

		/////////////////////// start qz <: qx adapt qp ///////////////////////
				
		// for each z,p pair process s(x), s(z), and s(p)
		for(GraphElement parametersPassedEdge : parametersPassedEdges){
			GraphElement z = parametersPassedEdge.getNode(EdgeDirection.FROM);
			GraphElement p = parametersPassedEdge.getNode(EdgeDirection.TO);
			Set<ImmutabilityTypes> zTypes = getTypes(z);
			Set<ImmutabilityTypes> pTypes = getTypes(p);
			
			// process s(x)
			xTypesToRemove = new HashSet<ImmutabilityTypes>();
			for(ImmutabilityTypes xType : xTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes zType : zTypes){
					for(ImmutabilityTypes pType : pTypes){
						ImmutabilityTypes xAdaptedP = ImmutabilityTypes.getAdaptedMethodViewpoint(xType, pType);
						if(xAdaptedP.compareTo(zType) >= 0){
							isSatisfied = true;
							break satisfied;
						}
					}
				}
				if(!isSatisfied){
					xTypesToRemove.add(xType);
				}
			}
			if(removeTypes(x, xTypesToRemove)){
				typesChanged = true;
			}
			
			// process s(z)
			Set<ImmutabilityTypes> zTypesToRemove = new HashSet<ImmutabilityTypes>();
			for(ImmutabilityTypes zType : zTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes xType : xTypes){
					for(ImmutabilityTypes pType : pTypes){
						ImmutabilityTypes xAdaptedP = ImmutabilityTypes.getAdaptedMethodViewpoint(xType, pType);
						if(xAdaptedP.compareTo(zType) >= 0){
							isSatisfied = true;
							break satisfied;
						}
					}
				}
				if(!isSatisfied){
					zTypesToRemove.add(zType);
				}
			}
			if(removeTypes(z, zTypesToRemove)){
				typesChanged = true;
			}
			
			// process s(p)
			Set<ImmutabilityTypes> pTypesToRemove = new HashSet<ImmutabilityTypes>();
			for(ImmutabilityTypes pType : pTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes xType : xTypes){
					for(ImmutabilityTypes zType : zTypes){
						ImmutabilityTypes xAdaptedP = ImmutabilityTypes.getAdaptedMethodViewpoint(xType, pType);
						if(xAdaptedP.compareTo(zType) >= 0){
							isSatisfied = true;
							break satisfied;
						}
					}
				}
				if(!isSatisfied){
					pTypesToRemove.add(pType);
				}
			}
			if(removeTypes(p, pTypesToRemove)){
				typesChanged = true;
			}
		}
		
		/////////////////////// end qz <: qx adapt qp ///////////////////////
		
		return typesChanged;
	}
	
	/**
	 * Sets the type qualifier for a graph element
	 * @param ge
	 * @param qualifier
	 * @return Returns true if the type qualifier changed
	 */
	private static boolean removeTypes(GraphElement ge, Set<ImmutabilityTypes> typesToRemove){
		Set<ImmutabilityTypes> typeSet = getTypes(ge);
		String logMessage = "Remove: " + typesToRemove.toString() + " from " + typeSet.toString() + " for " + ge.getAttr(XCSG.name);
		boolean typesChanged = typeSet.removeAll(typesToRemove);
		if(typesChanged){
			Log.info(logMessage);
		}
		return typesChanged;
	}
	
	/**
	 * Sets the type qualifier for a graph element
	 * @param ge
	 * @param qualifier
	 * @return Returns true if the type qualifier changed
	 */
	@SuppressWarnings("unused")
	private static boolean removeTypes(GraphElement ge, ImmutabilityTypes... types){
		HashSet<ImmutabilityTypes> typesToRemove = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes type : types){
			typesToRemove.add(type);
		}
		return removeTypes(ge, typesToRemove);
	}
	
	/**
	 * Sets the type qualifier for a graph element
	 * @param ge
	 * @param qualifier
	 * @return Returns true if the type qualifier changed
	 */
	private static boolean setTypes(GraphElement ge, Set<ImmutabilityTypes> typesToSet){
		Set<ImmutabilityTypes> typeSet = getTypes(ge);
		
		String logMessage = "Set: " + typeSet.toString() + " to " + typesToSet.toString() + " for " + ge.getAttr(XCSG.name);
		
		boolean typesChanged;
		if(typeSet.containsAll(typesToSet) && typesToSet.containsAll(typeSet)){
			typesChanged = false;
		} else {
			typeSet.clear();
			typeSet.addAll(typesToSet);
			typesChanged = true;
		}
		
		if(typesChanged){
			Log.info(logMessage);
		}
		
		return typesChanged;
	}
	
	/**
	 * Sets the type qualifier for a graph element
	 * @param ge
	 * @param qualifier
	 * @return Returns true if the type qualifier changed
	 */
	private static boolean setTypes(GraphElement ge, ImmutabilityTypes... types){
		HashSet<ImmutabilityTypes> typesToSet = new HashSet<ImmutabilityTypes>();
		for(ImmutabilityTypes type : types){
			typesToSet.add(type);
		}
		return setTypes(ge, typesToSet);
	}
	
	@SuppressWarnings("unchecked")
	public static Set<ImmutabilityTypes> getTypes(GraphElement ge){
		if(ge.hasAttr(IMMUTABILITY_TYPES)){
			return (Set<ImmutabilityTypes>) ge.getAttr(IMMUTABILITY_TYPES);
		} else {
			HashSet<ImmutabilityTypes> qualifiers = new HashSet<ImmutabilityTypes>();
			
			Q typeOfEdges = Common.universe().edgesTaggedWithAny(XCSG.TypeOf);
			GraphElement geType = typeOfEdges.successors(Common.toQ(ge)).eval().nodes().getFirst();
			
			GraphElement nullType = Common.universe().nodesTaggedWithAny(XCSG.Java.NullType).eval().nodes().getFirst();
			if(ge.equals(nullType)){
				// assignments of null mutate objects
				// see https://github.com/proganalysis/type-inference/blob/master/inference-framework/checker-framework/checkers/src/checkers/inference2/reim/ReimChecker.java#L181
				qualifiers.add(ImmutabilityTypes.MUTABLE);
			} else if(ge.taggedWith(XCSG.Instantiation) || ge.taggedWith(XCSG.ArrayInstantiation)){
				// Type Rule 1 - TNEW
				// return type of a constructor is only mutable
				// x = new C(); // no effect on qualifier to x
				qualifiers.add(ImmutabilityTypes.MUTABLE);
			} else if(isDefaultReadonlyType(ge) || isDefaultReadonlyType(geType)){
				// several java objects are readonly for all practical purposes
				qualifiers.add(ImmutabilityTypes.READONLY);
			} else if(ge.taggedWith(XCSG.MasterReturn)){
				// Section 2.4 of Reference 1
				// "Method returns are initialized S(ret) = {readonly, polyread} for each method m"
				qualifiers.add(ImmutabilityTypes.POLYREAD);
				qualifiers.add(ImmutabilityTypes.READONLY);
			} else if(ge.taggedWith(XCSG.Field)){
				// Section 2.4 of Reference 1
				// "Fields are initialized to S(f) = {readonly, polyread}"
				qualifiers.add(ImmutabilityTypes.POLYREAD);
				qualifiers.add(ImmutabilityTypes.READONLY);
			} else {
				// Section 2.4 of Reference 1
				// "All other references are initialized to the maximal
				// set of qualifiers, i.e. S(x) = {readonly, polyread, mutable}"
				qualifiers.add(ImmutabilityTypes.POLYREAD);
				qualifiers.add(ImmutabilityTypes.READONLY);
				qualifiers.add(ImmutabilityTypes.MUTABLE);
			}

			ge.putAttr(IMMUTABILITY_TYPES, qualifiers);
			return qualifiers;
		}
	}
	
	/**
	 * Returns the fields of containers that are types of the type for the given field 
	 * and the resulting reachable fields
	 * @param field
	 * @return
	 */
	public static AtlasSet<GraphElement> getContainerFields(GraphElement field){
		Q containsEdges = Common.universe().edgesTaggedWithAny(XCSG.Contains);
		Q typeOfEdges = Common.universe().edgesTaggedWithAny(XCSG.TypeOf);
		Q supertypeEdges = Common.universe().edgesTaggedWithAny(XCSG.Supertype);
		
		AtlasSet<GraphElement> fields = new AtlasHashSet<GraphElement>();
		fields.add(field);
		boolean foundNewFields = false;
		do {
			Q privateFields = Common.toQ(fields).nodesTaggedWithAny(XCSG.privateVisibility);
			Q accessibleFields = Common.toQ(fields).difference(privateFields);
			Q accessibleFieldContainers = containsEdges.predecessors(accessibleFields);
			Q accessibleFieldContainerSubtypes = supertypeEdges.reverse(accessibleFieldContainers);
			Q privateFieldContainers = containsEdges.predecessors(privateFields);
			AtlasSet<GraphElement> reachableFields = typeOfEdges.predecessors(accessibleFieldContainerSubtypes.union(privateFieldContainers)).nodesTaggedWithAny(XCSG.InstanceVariable).eval().nodes();
			foundNewFields = fields.addAll(reachableFields);
		} while(foundNewFields);
		
		return fields;
	}
	
	/**
	 * Returns the least common ancestor for two sets of ImmutabilityTypes
	 * @param a
	 * @param b
	 * @return
	 */
	public static ImmutabilityTypes leastCommonAncestor(Set<ImmutabilityTypes> a, Set<ImmutabilityTypes> b){
		HashSet<ImmutabilityTypes> commonTypes = new HashSet<ImmutabilityTypes>();
		commonTypes.addAll(a);
		commonTypes.retainAll(b);
		LinkedList<ImmutabilityTypes> orderedTypes = new LinkedList<ImmutabilityTypes>();
		orderedTypes.addAll(commonTypes);
		Collections.sort(orderedTypes);
		return orderedTypes.getLast();
	}
	
	/**
	 * Returns true if the method is pure
	 * @param method
	 */
	public static boolean isPureMethod(GraphElement method){
		if(!method.taggedWith(XCSG.Method)){
			return false;
		} else if(isPureMethodDefault(method)){
			return true;
		} else {
			boolean isPure = true;
			// TODO: check if "this" receiver object in any callsite is not mutable
			// TODO: check if parameter is not mutable
			// TODO: check if static immutability type is not mutable
			return isPure;
		}
	}
	
	private static boolean isPureMethodDefault(GraphElement method){
		// from : https://github.com/SoftwareEngineeringToolDemos/FSE-2012-ReImInfer/blob/master/inference-framework/checker-framework/checkers/src/checkers/inference/reim/ReimChecker.java
//		  defaultPurePatterns.add(Pattern.compile(".*\\.equals\\(java\\.lang\\.Object\\)$"));
//        defaultPurePatterns.add(Pattern.compile(".*\\.hashCode\\(\\)$"));
//        defaultPurePatterns.add(Pattern.compile(".*\\.toString\\(\\)$"));
//        defaultPurePatterns.add(Pattern.compile(".*\\.compareTo\\(.*\\)$"));
		return false;
	}
	
	private static boolean isDefaultReadonlyType(GraphElement type) {
		if(type == null){
			return false;
		}
		
		// primitive types
		if(type.taggedWith(XCSG.Primitive)){
			return true;
		}
		
		// autoboxing
		GraphElement integerType = Common.typeSelect("java.lang", "Integer").eval().nodes().getFirst();
		GraphElement longType = Common.typeSelect("java.lang", "Long").eval().nodes().getFirst();
		GraphElement shortType = Common.typeSelect("java.lang", "Short").eval().nodes().getFirst();
		GraphElement booleanType = Common.typeSelect("java.lang", "Boolean").eval().nodes().getFirst();
		GraphElement byteType = Common.typeSelect("java.lang", "Byte").eval().nodes().getFirst();
		GraphElement doubleType = Common.typeSelect("java.lang", "Double").eval().nodes().getFirst();
		GraphElement floatType = Common.typeSelect("java.lang", "Float").eval().nodes().getFirst();
		GraphElement characterType = Common.typeSelect("java.lang", "Character").eval().nodes().getFirst();
		if (type.equals(integerType) 
				|| type.equals(longType) 
				|| type.equals(shortType) 
				|| type.equals(booleanType) 
				|| type.equals(byteType)
				|| type.equals(doubleType) 
				|| type.equals(floatType) 
				|| type.equals(characterType)) {
			return true;
		}
		
		// a few other objects are special cases for all practical purposes
		if(type.equals(Common.typeSelect("java.lang", "String").eval().nodes().getFirst())){
			return true;
		} else if(type.equals(Common.typeSelect("java.lang", "Number").eval().nodes().getFirst())){
			return true;
		} else if(type.equals(Common.typeSelect("java.util.concurrent.atomic", "AtomicInteger").eval().nodes().getFirst())){
			return true;
		} else if(type.equals(Common.typeSelect("java.util.concurrent.atomic", "AtomicLong").eval().nodes().getFirst())){
			return true;
		} else if(type.equals(Common.typeSelect("java.math", "BigDecimal").eval().nodes().getFirst())){
			return true;
		} else if(type.equals(Common.typeSelect("java.math", "BigInteger").eval().nodes().getFirst())){
			return true;
		}
		
		return false;
	}
	
}
