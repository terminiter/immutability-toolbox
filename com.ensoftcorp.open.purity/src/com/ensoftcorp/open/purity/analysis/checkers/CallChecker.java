package com.ensoftcorp.open.purity.analysis.checkers;

import static com.ensoftcorp.open.purity.core.Utilities.getTypes;
import static com.ensoftcorp.open.purity.core.Utilities.removeStaticTypes;
import static com.ensoftcorp.open.purity.core.Utilities.removeTypes;

import java.util.EnumSet;
import java.util.Set;

import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.EdgeDirection;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.log.Log;
import com.ensoftcorp.atlas.core.query.Attr.Edge;
import com.ensoftcorp.atlas.core.query.Q;
import com.ensoftcorp.atlas.core.script.Common;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.open.purity.core.ImmutabilityTypes;
import com.ensoftcorp.open.purity.core.Utilities;
import com.ensoftcorp.open.purity.ui.PurityPreferences;

public class CallChecker {

	/**
	 * Let, x=y.m(z)
	 * @param x
	 * @param y
	 * @param identity
	 * @param method
	 * @param ret
	 * @param parametersPassedEdges
	 * @return
	 */
	public static boolean handleCall(GraphElement x, GraphElement y, GraphElement identity, GraphElement method, GraphElement ret, AtlasSet<GraphElement> parametersPassedEdges) {
		
		if(x==null){
			Log.warning("x is null!");
			return false;
		}
		
		if(y==null){
			Log.warning("y is null!");
			return false;
		}
		
		if(identity==null){
			Log.warning("identity is null!");
			return false;
		}
		
		if(ret==null){
			Log.warning("return is null!");
			return false;
		}
		
		if(PurityPreferences.isInferenceRuleLoggingEnabled()) Log.info("TCALL (x=y.m(z), x=" + x.getAttr(XCSG.name) + ", y=" + y.getAttr(XCSG.name) + ", m=" + method.getAttr("##signature") + ")");
		
		boolean typesChanged = false;
		Set<ImmutabilityTypes> xTypes = getTypes(x);
		Set<ImmutabilityTypes> yTypes = getTypes(y);
		Set<ImmutabilityTypes> identityTypes = getTypes(identity);
		Set<ImmutabilityTypes> retTypes = getTypes(ret);
		
		boolean isPolyreadField = x.taggedWith(XCSG.Field) && (xTypes.contains(ImmutabilityTypes.POLYREAD) && xTypes.size() == 1);
		boolean isMutableReference = !x.taggedWith(XCSG.Field) && (xTypes.contains(ImmutabilityTypes.MUTABLE) && xTypes.size() == 1);
		
		// if x is a field and polyread then the return value must be polyread
		// if x is a reference and mutable then the return value must be polyread
		// whether the field or reference is polyread or mutable we know know that it
		// is at least not readonly
		if(isPolyreadField || isMutableReference){
			if(removeTypes(ret, ImmutabilityTypes.READONLY)){
				typesChanged = true;
			}
			// if the return value is a field then the field and its container fields must be mutable as well
			Q localDataFlowEdges = Common.universe().edgesTaggedWithAny(XCSG.LocalDataFlow);
			Q returnValues = localDataFlowEdges.predecessors(Common.toQ(ret));
			Q fieldValues = localDataFlowEdges.predecessors(returnValues).nodesTaggedWithAny(XCSG.InstanceVariableValue, Utilities.CLASS_VARIABLE_VALUE);
			Q interproceduralDataFlowEdges = Common.universe().edgesTaggedWithAny(XCSG.InterproceduralDataFlow);
			Q fields = interproceduralDataFlowEdges.predecessors(fieldValues);
			for(GraphElement field : fields.eval().nodes()){
				for(GraphElement container : Utilities.getAccessedContainers(field)){
					if(removeTypes(container, ImmutabilityTypes.READONLY)){
						typesChanged = true;
					}
					if(container.taggedWith(XCSG.ClassVariable)){
						if(removeStaticTypes(Utilities.getContainingMethod(x), ImmutabilityTypes.READONLY, ImmutabilityTypes.POLYREAD)){
							typesChanged = true;
						}
					}
				}
			}
		}	
		
		/////////////////////// start qx adapt qret <: qx /////////////////////// 
		
		if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process Constraint qx adapt qret <: qx");
		
		// process s(x)
		if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(x)");
		Set<ImmutabilityTypes> xTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
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
		if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(ret)");
		Set<ImmutabilityTypes> retTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
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
		
		if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process Constraint qy <: qx adapt qthis");
		
		// process s(y)
		if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(y)");
		Set<ImmutabilityTypes> yTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
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
		if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(x)");
		xTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
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
		if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(identity)");
		Set<ImmutabilityTypes> identityTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
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
				
		if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process Constraint qz <: qx adapt qp");
		
		// for each z,p pair process s(x), s(z), and s(p)
		for(GraphElement parametersPassedEdge : parametersPassedEdges){
			GraphElement z = parametersPassedEdge.getNode(EdgeDirection.FROM);
			GraphElement p = parametersPassedEdge.getNode(EdgeDirection.TO);
			Set<ImmutabilityTypes> zTypes = getTypes(z);
			Set<ImmutabilityTypes> pTypes = getTypes(p);
			
			// process s(x)
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(x)");
			xTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
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
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(z)");
			Set<ImmutabilityTypes> zTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
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
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(p)");
			Set<ImmutabilityTypes> pTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
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
		
		// check if method overrides another method (of course this will be empty for static methods)
		Q overridesEdges = Common.universe().edgesTaggedWithAny(XCSG.Overrides);
		GraphElement overriddenMethod = overridesEdges.successors(Common.toQ(method)).eval().nodes().getFirst();
		if(overriddenMethod != null){
			if(PurityPreferences.isInferenceRuleLoggingEnabled()) Log.info("TCALL (Overridden Method)");
			
			// Method (method) -Contains-> ReturnValue (ret)
			GraphElement overriddenMethodReturn = Common.toQ(overriddenMethod).children().nodesTaggedWithAny(XCSG.ReturnValue).eval().nodes().getFirst();
			Set<ImmutabilityTypes> overriddenRetTypes = getTypes(overriddenMethodReturn);
			
			// constraint: overriddenReturn <: return
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process Constraint overriddenReturn <: return");
			
			// process s(ret)
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(ret)");
			retTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
			for(ImmutabilityTypes retType : retTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes overriddenRetType : overriddenRetTypes){
					if(retType.compareTo(overriddenRetType) >= 0){
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
			
			// process s(overriddenRet)
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(overriddenRet)");
			EnumSet<ImmutabilityTypes> overriddenRetTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
			for(ImmutabilityTypes overriddenRetType : overriddenRetTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes retType : retTypes){
					if(retType.compareTo(overriddenRetType) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
				if(!isSatisfied){
					overriddenRetTypesToRemove.add(overriddenRetType);
				}
			}
			if(removeTypes(overriddenMethodReturn, overriddenRetTypesToRemove)){
				typesChanged = true;
			}
			
			// Method (method) -Contains-> Identity
			GraphElement overriddenMethodIdentity = Common.toQ(overriddenMethod).children().nodesTaggedWithAny(XCSG.Identity).eval().nodes().getFirst();
			Set<ImmutabilityTypes> overriddenIdentityTypes = getTypes(overriddenMethodIdentity);

			// constraint: this <: overriddenThis 
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process Constraint this <: overriddenThis");
			
			// process s(this)
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(this)");
			identityTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
			for(ImmutabilityTypes identityType : identityTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes overriddenIdentityType : overriddenIdentityTypes){
					if(overriddenIdentityType.compareTo(identityType) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
				if(!isSatisfied){
					identityTypesToRemove.add(identityType);
				}
			}
			if(removeTypes(identity, identityTypesToRemove)){
				typesChanged = true;
			}
			
			// process s(overriddenRet)
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(overriddenRet)");
			EnumSet<ImmutabilityTypes> overriddenIdentityTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
			for(ImmutabilityTypes overriddenIdentityType : overriddenIdentityTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes identityType : identityTypes){
					if(overriddenIdentityType.compareTo(identityType) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
				if(!isSatisfied){
					overriddenIdentityTypesToRemove.add(overriddenIdentityType);
				}
			}
			if(removeTypes(overriddenMethodIdentity, overriddenIdentityTypesToRemove)){
				typesChanged = true;
			}

			// Method (method) -Contains-> Parameter (p1, p2, ...)
			AtlasSet<GraphElement> overriddenMethodParameters = Common.toQ(overriddenMethod).children().nodesTaggedWithAny(XCSG.Parameter).eval().nodes();
			
			// get the parameters of the method
			AtlasSet<GraphElement> parameters = new AtlasHashSet<GraphElement>();
			for(GraphElement parametersPassedEdge : parametersPassedEdges){
				GraphElement p = parametersPassedEdge.getNode(EdgeDirection.TO);
				parameters.add(p);
			}
			
			// for each parameter and overridden parameter pair
			// constraint: p <: pOverriden
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process Constraint p <: pOverriden");
			long numParams = overriddenMethodParameters.size();
			if(numParams > 0){
				for(int i=0; i<numParams; i++){
					GraphElement p = Common.toQ(parameters).selectNode(XCSG.parameterIndex, i).eval().nodes().getFirst();
					GraphElement pOverridden = Common.toQ(overriddenMethodParameters).selectNode(XCSG.parameterIndex, i).eval().nodes().getFirst();
					
					Set<ImmutabilityTypes> pTypes = getTypes(p);
					Set<ImmutabilityTypes> pOverriddenTypes = getTypes(pOverridden);
					
					// process s(p)
					if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(p)");
					Set<ImmutabilityTypes> pTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
					for(ImmutabilityTypes pType : pTypes){
						boolean isSatisfied = false;
						satisfied:
						for(ImmutabilityTypes pOverriddenType : pOverriddenTypes){
							if(pOverriddenType.compareTo(pType) >= 0){
								isSatisfied = true;
								break satisfied;
							}
						}
						if(!isSatisfied){
							pTypesToRemove.add(pType);
						}
					}
					if(removeTypes(p, pTypesToRemove)){
						typesChanged = true;
					}
					
					// process s(pOverridden)
					if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(pOverridden)");
					Set<ImmutabilityTypes> pOverriddenTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
					for(ImmutabilityTypes pOverriddenType : pOverriddenTypes){
						boolean isSatisfied = false;
						satisfied:
						for(ImmutabilityTypes pType : pTypes){
							if(pOverriddenType.compareTo(pType) >= 0){
								isSatisfied = true;
								break satisfied;
							}
						}
						if(!isSatisfied){
							pOverriddenTypesToRemove.add(pOverriddenType);
						}
					}
					if(removeTypes(pOverridden, pOverriddenTypesToRemove)){
						typesChanged = true;
					}
				}
			}
		}
		
		// if types have changed then type constraints need to be checked
		// for each callsite of this method or the overriden method to satisfy 
		// constraints on receivers and parameters passed
		// - note that we technically only need to do this for the callsites
		// that are not involved in an assignment because callsites of the
		// form x = y.m(z) are already present in the worklist, but propagating
		// this information up front won't hurt especially if we already know
		// it needs to be propagated
		// - note also this could be refined with a precise call graph instead of 
		// a CHA, but we'd also have to update the callsite resolution which brought
		// us to this point in the first place
		
		// TODO: should I actually only do this for callsites without assignments??? it might ruin adaptations...
		
		if(typesChanged){
			if(method.taggedWith(XCSG.InstanceMethod)){
				handleInstanceMethodCallsites(method);
				if(overriddenMethod != null){
					handleInstanceMethodCallsites(overriddenMethod);
				}
			} else if(method.taggedWith(XCSG.ClassMethod)){
				handleClassMethodCallsites(method);
			}
		}
		
		return typesChanged;
	}

	/**
	 * Checks and satisfies constraints on callsites to the given target instance method
	 * Let r.c(z) be a callsite to method this.m(p)
	 * 
	 * Constraint 1) this <: r
	 * Constraint 2) z1 <: p1, z2 <: p2, z3 <: p3 ...
	 * 
	 * @param method Method to check the constraints of corresponding callsites
	 */
	private static boolean handleInstanceMethodCallsites(GraphElement method) {
		boolean typesChanged = false;
		
		GraphElement identity = Common.toQ(method).children().nodesTaggedWithAny(XCSG.Identity).eval().nodes().getFirst();
		Set<ImmutabilityTypes> identityTypes = getTypes(identity);
		Q localDFEdges = Common.universe().edgesTaggedWithAny(XCSG.LocalDataFlow);
		Q identityPassedToEdges = Common.universe().edgesTaggedWithAny(XCSG.IdentityPassedTo);
		Q perControlFlowEdges = Common.universe().edgesTaggedWithAny(Edge.PER_CONTROL_FLOW);
		Q callsiteControlFlowBlocks = perControlFlowEdges.predecessors(Common.toQ(method));
		Q callsites = callsiteControlFlowBlocks.children().nodesTaggedWithAny(XCSG.CallSite);
		for(GraphElement callsite : callsites.eval().nodes()){
			// IdentityPass (.this) -IdentityPassedTo-> CallSite (m)
			Q identityPass = identityPassedToEdges.predecessors(Common.toQ(callsite));
			
			// Receiver (r) -LocalDataFlow-> IdentityPass (.this)
			GraphElement r = localDFEdges.predecessors(identityPass).eval().nodes().getFirst();
			Set<ImmutabilityTypes> rTypes = getTypes(r);
			
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process Callsite Constraint qthis <: qr");
			
			// process s(this)
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(this)");
			Set<ImmutabilityTypes> identityTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
			for(ImmutabilityTypes identityType : identityTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes rType : rTypes){
					if(rType.compareTo(identityType) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
				if(!isSatisfied){
					identityTypesToRemove.add(identityType);
				}
			}
			if(removeTypes(identity, identityTypesToRemove)){
				typesChanged = true;
			}
			
			// process s(r)
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info("Process s(r)");
			Set<ImmutabilityTypes> rTypesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
			for(ImmutabilityTypes rType : rTypes){
				boolean isSatisfied = false;
				satisfied:
				for(ImmutabilityTypes identityType : identityTypes){
					if(rType.compareTo(identityType) >= 0){
						isSatisfied = true;
						break satisfied;
					}
				}
				if(!isSatisfied){
					rTypesToRemove.add(rType);
				}
			}
			if(removeTypes(r, rTypesToRemove)){
				typesChanged = true;
			}
		}
		
		return typesChanged;
	}
	
	/**
	 * Checks and satisfies constraints on callsites to the given target class (static) method
	 * Let c(z) be a callsite to method m(p)
	 * 
	 * Constraint 1) z1 <: p1, z2 <: p2, z3 <: p3 ...
	 * 
	 * @param method Method to check the constraints of corresponding callsites
	 */
	private static boolean handleClassMethodCallsites(GraphElement method) {
		boolean typesChanged = false;
		
		Q perControlFlowEdges = Common.universe().edgesTaggedWithAny(Edge.PER_CONTROL_FLOW);
		Q callsiteControlFlowBlocks = perControlFlowEdges.predecessors(Common.toQ(method));
		Q callsites = callsiteControlFlowBlocks.children().nodesTaggedWithAny(XCSG.CallSite);
		for(GraphElement callsite : callsites.eval().nodes()){
			// TODO: check parameters
		}
		
		return typesChanged;
	}
	
}