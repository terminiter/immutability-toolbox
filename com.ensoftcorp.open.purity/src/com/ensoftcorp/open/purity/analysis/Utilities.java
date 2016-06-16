package com.ensoftcorp.open.purity.analysis;

import java.util.EnumSet;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.EdgeDirection;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.NodeDirection;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.query.Q;
import com.ensoftcorp.atlas.core.script.Common;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.open.purity.log.Log;
import com.ensoftcorp.open.purity.preferences.PurityPreferences;

public class Utilities {
	
	// caching for some common graph types
	private static boolean cacheInitialized = false;
	private static AtlasSet<GraphElement> defaultReadonlyTypes;
	
	private static void initializeCache(IProgressMonitor monitor) {
		// initialize the cache of default readonly types
		defaultReadonlyTypes = new AtlasHashSet<GraphElement>();
		
		// autoboxing
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Integer").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Long").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Short").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Boolean").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Byte").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Double").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Float").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Character").eval().nodes().getFirst());
		
		// a few other objects are special cases for all practical purposes
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "String").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.lang", "Number").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.util.concurrent.atomic", "AtomicInteger").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.util.concurrent.atomic", "AtomicLong").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.math", "BigDecimal").eval().nodes().getFirst());
		defaultReadonlyTypes.add(Common.typeSelect("java.math", "BigInteger").eval().nodes().getFirst());
	}
	
	/**
	 * Returns true if the given type is a default readonly type
	 * @param type
	 * @return
	 */
	public static boolean isDefaultReadonlyType(GraphElement type) {
		if(type == null){
			return false;
		}
		if(!cacheInitialized){
			initializeCache(new NullProgressMonitor());
		}
		return type.taggedWith(XCSG.Primitive) || defaultReadonlyTypes.contains(type);
	}
	
	/**
	 * Used as an attribute key to temporarily compute the potential immutability qualifiers
	 */
	public static final String IMMUTABILITY_QUALIFIERS = "IMMUTABILITY_QUALIFIERS";
	
	// TODO: bug EnSoft to make tags like this...
	public static final String CLASS_VARIABLE_ASSIGNMENT = "CLASS_VARIABLE_ASSIGNMENT";
	public static final String CLASS_VARIABLE_VALUE = "CLASS_VARIABLE_VALUE";
	public static final String CLASS_VARIABLE_ACCESS = "CLASS_VARIABLE_ACCESS";
	
	/**
	 * Adds CLASS_VARIABLE_ASSIGNMENT, CLASS_VARIABLE_VALUE, and CLASS_VARIABLE_ACCESS
	 * tags to reads/writes on static variables
	 */
	public static void addClassVariableAccessTags() {
		if(PurityPreferences.isGeneralLoggingEnabled()) Log.info("Adding class variable access tags...");
		Q classVariables = Common.universe().nodesTaggedWithAny(XCSG.ClassVariable);
		Q interproceduralDataFlowEdges = Common.universe().edgesTaggedWithAny(XCSG.InterproceduralDataFlow);
		AtlasSet<GraphElement> classVariableAssignments = interproceduralDataFlowEdges.predecessors(classVariables).eval().nodes();
		for(GraphElement classVariableAssignment : classVariableAssignments){
			classVariableAssignment.tag(CLASS_VARIABLE_ASSIGNMENT);
			classVariableAssignment.tag(CLASS_VARIABLE_ACCESS);
		}
		AtlasSet<GraphElement> classVariableValues = interproceduralDataFlowEdges.successors(classVariables).eval().nodes();
		for(GraphElement classVariableValue : classVariableValues){
			classVariableValue.tag(CLASS_VARIABLE_VALUE);
			classVariableValue.tag(CLASS_VARIABLE_ACCESS);
		}
		if(PurityPreferences.isGeneralLoggingEnabled()) Log.info("Added class variable access tags.");
	}
	
	/**
	 * Removes CLASS_VARIABLE_ASSIGNMENT, CLASS_VARIABLE_VALUE, and CLASS_VARIABLE_ACCESS
	 * tags to reads/writes on static variables
	 */
	public static void removeClassVariableAccessTags() {
		if(PurityPreferences.isGeneralLoggingEnabled()) Log.info("Removing class variable access tags...");
		Q classVariables = Common.universe().nodesTaggedWithAny(XCSG.ClassVariable);
		Q interproceduralDataFlowEdges = Common.universe().edgesTaggedWithAny(XCSG.InterproceduralDataFlow);
		AtlasSet<GraphElement> classVariableAssignments = interproceduralDataFlowEdges.predecessors(classVariables).eval().nodes();
		for(GraphElement classVariableAssignment : classVariableAssignments){
			classVariableAssignment.tags().remove(CLASS_VARIABLE_ASSIGNMENT);
			classVariableAssignment.tags().remove(CLASS_VARIABLE_ACCESS);
		}
		AtlasSet<GraphElement> classVariableValues = interproceduralDataFlowEdges.successors(classVariables).eval().nodes();
		for(GraphElement classVariableValue : classVariableValues){
			classVariableValue.tags().remove(CLASS_VARIABLE_VALUE);
			classVariableValue.tags().remove(CLASS_VARIABLE_ACCESS);
		}
		if(PurityPreferences.isGeneralLoggingEnabled()) Log.info("Removed class variable access tags.");
	}
	
	/**
	 * Given a callsite this method returns the invoked method signature
	 * @param callsite
	 * @return
	 */
	public static GraphElement getInvokedMethodSignature(GraphElement callsite) {
		// XCSG.InvokedSignature connects a dynamic dispatch to its signature method
		// XCSG.InvokedFunction connects a static dispatch to it actual target method
		Q invokedEdges = Common.universe().edgesTaggedWithAny(XCSG.InvokedSignature, XCSG.InvokedFunction);
		GraphElement method = invokedEdges.successors(Common.toQ(callsite)).eval().nodes().getFirst();
		return method;
	}
	
	/**
	 * Sets the type qualifier for a graph element
	 * @param ge
	 * @param qualifier
	 * @return Returns true if the type qualifier changed
	 */
	public static boolean removeTypes(GraphElement ge, Set<ImmutabilityTypes> typesToRemove){
		Set<ImmutabilityTypes> typeSet = getTypes(ge);
		String logMessage = "Remove: " + typesToRemove.toString() + " from " + typeSet.toString() + " for " + ge.getAttr(XCSG.name);
		boolean typesChanged = typeSet.removeAll(typesToRemove);
		if(typesChanged){
			if(PurityPreferences.isDebugLoggingEnabled()) Log.info(logMessage);
			if(PurityPreferences.isDebugLoggingEnabled() && typeSet.isEmpty()) Log.warning("Remove result in an empty type set.");
		}
		return typesChanged;
	}
	
	/**
	 * Sets the type qualifier for a graph element
	 * @param ge
	 * @param qualifier
	 * @return Returns true if the type qualifier changed
	 */
	public static boolean removeTypes(GraphElement ge, ImmutabilityTypes... types){
		EnumSet<ImmutabilityTypes> typesToRemove = EnumSet.noneOf(ImmutabilityTypes.class);
		for(ImmutabilityTypes type : types){
			typesToRemove.add(type);
		}
		return removeTypes(ge, typesToRemove);
	}
	
	@SuppressWarnings("unchecked")
	public static Set<ImmutabilityTypes> getTypes(GraphElement ge){
		if(ge.hasAttr(IMMUTABILITY_QUALIFIERS)){
			return (Set<ImmutabilityTypes>) ge.getAttr(IMMUTABILITY_QUALIFIERS);
		} else {
			EnumSet<ImmutabilityTypes> qualifiers = getDefaultTypes(ge);
			ge.putAttr(IMMUTABILITY_QUALIFIERS, qualifiers);
			return qualifiers;
		}
	}
	
	public static GraphElement getObjectType(GraphElement ge) {
		Q typeOfEdges = Common.universe().edgesTaggedWithAny(XCSG.TypeOf);
		return typeOfEdges.successors(Common.toQ(ge)).eval().nodes().getFirst();
	}
	
	public static GraphElement parseReference(GraphElement ge){
		Q localDataFlowEdges = Common.universe().edgesTaggedWithAny(XCSG.LocalDataFlow);
		Q interproceduralDataFlowEdges = Common.universe().edgesTaggedWithAny(XCSG.InterproceduralDataFlow);
		GraphElement reference = ge;
		while(reference != null && !isTyped(reference)){
			// unwrap casts to reach reference
			if(reference.taggedWith(XCSG.Cast)){
				GraphElement current = reference;
				while(current.taggedWith(XCSG.Cast)){
					
					current = localDataFlowEdges.predecessors(Common.toQ(current)).eval().nodes().getFirst();
				}
				reference = current;
			}
			
			// get the field for instance and class variable values
			if(reference.taggedWith(XCSG.InstanceVariableValue) || reference.taggedWith(Utilities.CLASS_VARIABLE_VALUE)){
				reference = interproceduralDataFlowEdges.predecessors(Common.toQ(reference)).eval().nodes().getFirst();
			}
			
			// TODO: handle array components
		}
		return reference;
	}
	
	private static boolean isTyped(GraphElement ge){
		boolean isTyped = false;
		GraphElement nullType = Common.universe().nodesTaggedWithAny(XCSG.Java.NullType).eval().nodes().getFirst();
		if(ge.equals(nullType) || ge.taggedWith(XCSG.Null)){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.Literal) || isDefaultReadonlyType(getObjectType(ge))){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.Instantiation) || ge.taggedWith(XCSG.ArrayInstantiation)){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.MasterReturn)){
			isTyped = true;
		} else if (ge.taggedWith(XCSG.Parameter)){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.Identity)){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.InstanceVariable)){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.ClassVariable)){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.Method)){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.Operator)){
			isTyped = true;
		} else if(ge.taggedWith(XCSG.Assignment) || ge.taggedWith(XCSG.ParameterPass)){
			isTyped = true;
		}
		return isTyped;
	}
	
	public static EnumSet<ImmutabilityTypes> getDefaultTypes(GraphElement ge) {
		EnumSet<ImmutabilityTypes> qualifiers = EnumSet.noneOf(ImmutabilityTypes.class);
		
		GraphElement nullType = Common.universe().nodesTaggedWithAny(XCSG.Java.NullType).eval().nodes().getFirst();
		if(ge.equals(nullType) || ge.taggedWith(XCSG.Null)){
			// null does not modify the stack or heap so it is readonly
			// note however that assignments of nulls to a field can still mutate an object
			qualifiers.add(ImmutabilityTypes.READONLY);
			// however in order to satisfy constraints the other types should be initialized
			qualifiers.add(ImmutabilityTypes.POLYREAD);
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else if(ge.taggedWith(XCSG.Literal) || isDefaultReadonlyType(Utilities.getObjectType(ge))){
			// several java objects are readonly for all practical purposes
			// however in order to satisfy constraints the other types should be initialized
			qualifiers.add(ImmutabilityTypes.READONLY);
			qualifiers.add(ImmutabilityTypes.POLYREAD);
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else if(ge.taggedWith(XCSG.Instantiation) || ge.taggedWith(XCSG.ArrayInstantiation)){
			// Type Rule 1 - TNEW
			// return type of a constructor is only mutable
			// x = new C(); // no effect on qualifier to x
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else if(ge.taggedWith(XCSG.MasterReturn)){
			// Section 2.4 of Reference 1
			// "Method returns are initialized S(ret) = {readonly, polyread} for each method m"
			qualifiers.add(ImmutabilityTypes.READONLY);
			qualifiers.add(ImmutabilityTypes.POLYREAD);
		} else if (ge.taggedWith(XCSG.Parameter)){
			qualifiers.add(ImmutabilityTypes.READONLY);
			qualifiers.add(ImmutabilityTypes.POLYREAD);
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else if(ge.taggedWith(XCSG.Identity)){
			qualifiers.add(ImmutabilityTypes.READONLY);
//			qualifiers.add(ImmutabilityTypes.POLYREAD); // TODO: this is causing problems, but...the paper specifically says its an valid type...
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else if(ge.taggedWith(XCSG.InstanceVariable)){
			// Section 2.4 of Reference 1
			// "Fields are initialized to S(f) = {readonly, polyread}"
			qualifiers.add(ImmutabilityTypes.READONLY);
			qualifiers.add(ImmutabilityTypes.POLYREAD);
		} else if(ge.taggedWith(XCSG.ClassVariable)){
			// Section 3 of Reference 1
			// static fields are initialized to S(sf) = {readonly, mutable}
			qualifiers.add(ImmutabilityTypes.READONLY);
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else if(ge.taggedWith(XCSG.Method)){
			// Section 3 of Reference 1
			// methods can have a static type of {readonly, polyread, mutable}
			qualifiers.add(ImmutabilityTypes.READONLY);
			qualifiers.add(ImmutabilityTypes.POLYREAD);
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else if(ge.taggedWith(XCSG.Operator)){
			// the result of a primitive operation on primitives or primitive references is always readonly
			qualifiers.add(ImmutabilityTypes.READONLY);
			qualifiers.add(ImmutabilityTypes.POLYREAD);
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else if(ge.taggedWith(XCSG.Assignment) || ge.taggedWith(XCSG.ParameterPass)){
			// could be a ParameterPass or local reference
			// Section 2.4 of Reference 1
			// "All other references are initialized to the maximal
			// set of qualifiers, i.e. S(x) = {readonly, polyread, mutable}"
			// But, what does it mean for a local reference to be polyread? ~Ben
			qualifiers.add(ImmutabilityTypes.READONLY);
//			qualifiers.add(ImmutabilityTypes.POLYREAD);
			qualifiers.add(ImmutabilityTypes.MUTABLE);
		} else {
			RuntimeException e = new RuntimeException("Unexpected graph element: " + ge.address());
			Log.error("Unexpected graph element: " + ge.address(), e);
			throw e;
		}
		return qualifiers;
	}
	
	/**
	 * Returns the fields or local variables accessed for an instance variable access
	 * @param variableAccess
	 * @return
	 */
	public static AtlasSet<GraphElement> getAccessedContainers(GraphElement variableAccess){
		Q instanceVariableAccessedEdges = Common.universe().edgesTaggedWithAny(XCSG.InstanceVariableAccessed);
		Q variablesAccessed = instanceVariableAccessedEdges.reverse(Common.toQ(variableAccess));
		Q instanceVariablesAccessed = variablesAccessed.nodesTaggedWithAny(XCSG.InstanceVariableAccess);
		Q classVariablesAccessed = variablesAccessed.nodesTaggedWithAny(CLASS_VARIABLE_ACCESS);
		Q localVariables = variablesAccessed.difference(instanceVariablesAccessed, classVariablesAccessed);
		Q interproceduralDataFlowEdges = Common.universe().edgesTaggedWithAny(XCSG.InterproceduralDataFlow);
		Q fieldsAccessed = interproceduralDataFlowEdges.predecessors(instanceVariablesAccessed.union(classVariablesAccessed));
		return localVariables.union(fieldsAccessed).eval().nodes();
	}
	
	/**
	 * Returns the containing method of a given graph element or null if one is not found
	 * @param ge
	 * @return
	 */
	public static GraphElement getContainingMethod(GraphElement ge) {
		// NOTE: the enclosing method may be two steps or more above
		return getContainingNode(ge, XCSG.Method);
	}
	
	/**
	 * Find the next immediate containing node with the given tag.
	 * 
	 * @param node 
	 * @param containingTag
	 * @return the next immediate containing node, or null if none exists; never returns the given node
	 */
	public static GraphElement getContainingNode(GraphElement node, String containingTag) {
		if(node == null){
			return null;
		}
		
		while(true) {
			GraphElement containsEdge = Graph.U.edges(node, NodeDirection.IN).taggedWithAll(XCSG.Contains).getFirst();
			if(containsEdge == null){
				return null;
			}
			
			GraphElement parent = containsEdge.getNode(EdgeDirection.FROM);
			if(parent.taggedWith(containingTag)){
				return parent;
			}
			
			node = parent;
		}
	}
	
}
