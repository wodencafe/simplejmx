package com.j256.simplejmx.server;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ReflectionException;

import com.j256.simplejmx.common.JmxAttribute;
import com.j256.simplejmx.common.JmxOperation;
import com.j256.simplejmx.common.JmxResource;

/**
 * Wrapping of an object so we can dynamically expose its attributes and operations using annotations and reflection.
 * 
 * @author graywatson
 */
public class ReflectionMbean implements DynamicMBean {

	private final Object obj;
	private final Map<String, Method> fieldGetMap = new HashMap<String, Method>();
	private final Map<String, Method> fieldSetMap = new HashMap<String, Method>();
	private final Map<NameParams, Method> fieldOperationMap = new HashMap<NameParams, Method>();
	private final MBeanInfo mbeanInfo;

	public ReflectionMbean(Object obj) {
		this.obj = obj;
		this.mbeanInfo = buildMbeanInfo();
	}

	public Object getAttribute(String attribute) throws AttributeNotFoundException, ReflectionException {
		Method method = fieldGetMap.get(attribute);
		if (method == null) {
			throw new AttributeNotFoundException("Unknown attribute " + attribute);
		} else {
			try {
				return method.invoke(obj);
			} catch (Exception e) {
				throw new ReflectionException(e);
			}
		}
	}

	public AttributeList getAttributes(String[] attributeNames) {
		AttributeList returnList = new AttributeList();
		for (String name : attributeNames) {
			try {
				returnList.add(new Attribute(name, getAttribute(name)));
			} catch (Exception e) {
				returnList.add(new Attribute(name, e.getMessage()));
			}
		}
		return returnList;
	}

	public MBeanInfo getMBeanInfo() {
		return mbeanInfo;
	}

	public Object invoke(String actionName, Object[] params, String[] signatureTypes) throws MBeanException,
			ReflectionException {
		Method method = fieldOperationMap.get(new NameParams(actionName, signatureTypes));
		if (method == null) {
			throw new MBeanException(new IllegalArgumentException("Unknown action '" + actionName
					+ "' with parameter types " + Arrays.toString(signatureTypes)));
		} else {
			try {
				return method.invoke(obj, params);
			} catch (Exception e) {
				throw new ReflectionException(e);
			}
		}
	}

	public void setAttribute(Attribute attribute) throws AttributeNotFoundException, ReflectionException {
		Method method = fieldSetMap.get(attribute.getName());
		if (method == null) {
			throw new AttributeNotFoundException("Unknown attribute " + attribute);
		} else {
			try {
				method.invoke(obj, attribute.getValue());
			} catch (Exception e) {
				throw new ReflectionException(e);
			}
		}
	}

	public AttributeList setAttributes(AttributeList attributes) {
		AttributeList returnList = new AttributeList();
		for (Attribute attribute : attributes.asList()) {
			String name = attribute.getName();
			try {
				setAttribute(attribute);
				returnList.add(new Attribute(name, getAttribute(name)));
			} catch (Exception e) {
				returnList.add(new Attribute(name, e.getMessage()));
			}
		}
		return returnList;
	}

	/**
	 * Build our JMX information object by using reflection.
	 */
	private MBeanInfo buildMbeanInfo() {
		Class<?> clazz = obj.getClass();
		JmxResource jmxResource = clazz.getAnnotation(JmxResource.class);
		String desc;
		if (jmxResource == null || jmxResource.description() == null || jmxResource.description().length() == 0) {
			desc = "Jmx information about " + clazz;
		} else {
			desc = jmxResource.description();
		}
		Method[] methods = clazz.getMethods();
		discoverAttributes(methods);
		discoverOperations(methods);
		List<MBeanAttributeInfo> attributes = new ArrayList<MBeanAttributeInfo>();
		List<MBeanOperationInfo> operations = new ArrayList<MBeanOperationInfo>();
		// we have to go back because we need to match up the getters and setters
		for (Method method : methods) {
			JmxAttribute jmxAttribute = method.getAnnotation(JmxAttribute.class);
			if (jmxAttribute != null) {
				String methodName = method.getName();
				if (methodName.length() < 4) {
					// shouldn't get here but let's be careful out there
					continue;
				}
				String varName = buildMethodSuffix(methodName);
				Method getMethod = fieldGetMap.get(varName);
				if (methodName.startsWith("set") && getMethod != null) {
					/*
					 * We have to add the getter and the setter at the same time. So we only add them to the attribute
					 * list if this method is the getter or this is the setter and there is no getter. We don't want to
					 * add it twice.
					 */
					continue;
				}
				Method setMethod = fieldSetMap.get(varName);
				try {
					attributes.add(new MBeanAttributeInfo(varName, jmxAttribute.description(), getMethod, setMethod));
				} catch (IntrospectionException e) {
					// ignore this attribute I guess
				}
			} else {
				JmxOperation jmxOperation = method.getAnnotation(JmxOperation.class);
				if (jmxOperation != null) {
					MBeanParameterInfo[] parameterInfos = buildParameterInfos(method, jmxOperation);
					operations.add(new MBeanOperationInfo(method.getName(), jmxOperation.description(), parameterInfos,
							method.getReturnType().getName(), jmxOperation.action()));
				}
			}
		}

		return new MBeanInfo(clazz.getName(), desc, attributes.toArray(new MBeanAttributeInfo[attributes.size()]),
				null, operations.toArray(new MBeanOperationInfo[operations.size()]), null);
	}

	private MBeanParameterInfo[] buildParameterInfos(Method method, JmxOperation jmxOperation) {
		Class<?>[] types = method.getParameterTypes();
		MBeanParameterInfo[] parameterInfos = new MBeanParameterInfo[types.length];
		String[] parameterNames = jmxOperation.parameterNames();
		String[] parameterDescriptions = jmxOperation.parameterDescriptions();
		for (int i = 0; i < types.length; i++) {
			String name;
			if (i >= parameterNames.length) {
				name = "p" + i;
			} else {
				name = parameterNames[i];
			}
			String typeName = types[i].getName();
			String description;
			if (i >= parameterDescriptions.length) {
				description = "parameter #" + i + " of type: " + typeName;
			} else {
				description = parameterDescriptions[i];
			}
			parameterInfos[i] = new MBeanParameterInfo(name, typeName, description);
		}
		return parameterInfos;
	}

	/**
	 * Using reflection, find attribute methods from our object that will be exposed via JMX.
	 */
	private void discoverAttributes(Method[] methods) {
		for (Method method : methods) {
			JmxAttribute jmxAttribute = method.getAnnotation(JmxAttribute.class);
			if (jmxAttribute == null) {
				// skip it if no annotation
				continue;
			}
			String name = method.getName();
			if (name.length() < 4) {
				throw new IllegalArgumentException("Method '" + method + "' has a name that is too short");
			}
			String varName = buildMethodSuffix(name);
			Map<String, Method> fieldMap;
			if (name.startsWith("get")) {
				if (method.getParameterTypes().length != 0) {
					throw new IllegalArgumentException("Method '" + method + "' starts with 'get' but has arguments");
				}
				if (method.getReturnType() == void.class) {
					throw new IllegalArgumentException("Method '" + method
							+ "' starts with 'get' but does not return anything");
				}
				fieldMap = fieldGetMap;
			} else if (name.startsWith("set")) {
				if (method.getParameterTypes().length != 1) {
					throw new IllegalArgumentException("Method '" + method
							+ "' starts with 'set' but does not have 1 argument");
				}
				if (method.getReturnType() != void.class) {
					throw new IllegalArgumentException("Method '" + method
							+ "' starts with 'set' but does not return void");
				}
				fieldMap = fieldSetMap;
			} else {
				throw new IllegalArgumentException("Method '" + method
						+ "' is marked as an attribute but does not start with 'get' or 'set'");
			}
			fieldMap.put(varName, method);
		}
	}

	/**
	 * Using reflection, find operation methods from our object that will be exposed via JMX.
	 */
	private void discoverOperations(Method[] methods) {
		for (Method method : methods) {
			JmxOperation jmxOperation = method.getAnnotation(JmxOperation.class);
			if (jmxOperation == null) {
				continue;
			}
			String name = method.getName();
			if (name.startsWith("get") || name.startsWith("set")) {
				throw new IllegalArgumentException("Operation method " + method
						+ " cannot start with get or set.  Is this an attribute?");
			}
			Class<?>[] types = method.getParameterTypes();
			String[] stringTypes = new String[types.length];
			for (int i = 0; i < types.length; i++) {
				stringTypes[i] = types[i].toString();
			}
			NameParams nameParams = new NameParams(name, stringTypes);
			fieldOperationMap.put(nameParams, method);
		}
	}

	private String buildMethodSuffix(String name) {
		return Character.toLowerCase(name.charAt(3)) + name.substring(4);
	}

	/**
	 * Key class for our hashmap to find matching methods based on name and parameter list.
	 */
	private static class NameParams {
		String name;
		String[] paramTypes;
		public NameParams(String name, String[] paramTypes) {
			this.name = name;
			this.paramTypes = paramTypes;
		}

		@Override
		public int hashCode() {
			return 31 * (31 + name.hashCode()) + Arrays.hashCode(paramTypes);
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			NameParams other = (NameParams) obj;
			if (!name.equals(other.name)) {
				return false;
			}
			return Arrays.equals(paramTypes, other.paramTypes);
		}
	}
}