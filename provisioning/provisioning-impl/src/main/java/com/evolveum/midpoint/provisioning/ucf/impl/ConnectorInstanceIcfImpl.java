/*
 * Copyright (c) 2011 Evolveum
 * 
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 * 
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * Portions Copyrighted 2011 [name of copyright owner]
 */
package com.evolveum.midpoint.provisioning.ucf.impl;

import com.evolveum.midpoint.schema.XsdTypeConverter;
import com.evolveum.midpoint.api.logging.Trace;
import com.evolveum.midpoint.common.DebugUtil;
import com.evolveum.midpoint.common.object.ObjectTypeUtil;
import com.evolveum.midpoint.common.result.OperationResult;
import com.evolveum.midpoint.logging.TraceManager;
import com.evolveum.midpoint.provisioning.api.ProvisioningService;
import com.evolveum.midpoint.provisioning.impl.ProvisioningServiceImpl;
import com.evolveum.midpoint.provisioning.ucf.api.AttributeModificationOperation;
import com.evolveum.midpoint.provisioning.ucf.api.Change;
import com.evolveum.midpoint.provisioning.ucf.api.CommunicationException;
import com.evolveum.midpoint.provisioning.ucf.api.ConnectorInstance;
import com.evolveum.midpoint.provisioning.ucf.api.GenericFrameworkException;
import com.evolveum.midpoint.provisioning.ucf.api.ObjectNotFoundException;
import com.evolveum.midpoint.provisioning.ucf.api.Operation;
import com.evolveum.midpoint.provisioning.ucf.api.ResultHandler;
import com.evolveum.midpoint.provisioning.ucf.api.Token;
import com.evolveum.midpoint.provisioning.ucf.api.UcfException;
import com.evolveum.midpoint.schema.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.schema.exception.SchemaException;
import com.evolveum.midpoint.schema.exception.SystemException;
import com.evolveum.midpoint.schema.processor.Definition;
import com.evolveum.midpoint.schema.processor.Property;
import com.evolveum.midpoint.schema.processor.PropertyContainer;
import com.evolveum.midpoint.schema.processor.PropertyContainerDefinition;
import com.evolveum.midpoint.schema.processor.PropertyDefinition;
import com.evolveum.midpoint.schema.processor.ResourceObject;
import com.evolveum.midpoint.schema.processor.ResourceObjectAttribute;
import com.evolveum.midpoint.schema.processor.ResourceObjectAttributeDefinition;
import com.evolveum.midpoint.schema.processor.ResourceObjectDefinition;
import com.evolveum.midpoint.schema.processor.Schema;
import com.evolveum.midpoint.schema.processor.SchemaProcessorException;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectChangeDeletionType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectChangeModificationType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectChangeType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectModificationType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PropertyModificationType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PropertyModificationTypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ResourceType;
import com.evolveum.midpoint.xml.schema.SchemaConstants;
import com.evolveum.midpoint.xml.schema.XPathSegment;
import com.evolveum.midpoint.xml.schema.XPathType;

import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorSecurityException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.AttributeInfo.Flags;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClassUtil;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.common.security.GuardedString;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.naming.NameAlreadyBoundException;
import javax.naming.directory.SchemaViolationException;
import javax.xml.namespace.QName;

/**
 * Implementation of ConnectorInstance for ICF connectors.
 * 
 * This class implements the ConnectorInstance interface. The methods are
 * converting the data from the "midPoint semantics" as seen by the
 * ConnectorInstance interface to the "ICF semantics" as seen by the ICF
 * framework.
 * 
 * @author Radovan Semancik
 */
public class ConnectorInstanceIcfImpl implements ConnectorInstance {

	private static final String PASSWORD_ATTRIBUTE_NAME = "__PASSWORD__";
	private static final String ACCOUNT_OBJECTCLASS_LOCALNAME = "AccountObjectClass";
	private static final String GROUP_OBJECTCLASS_LOCALNAME = "GroupObjectClass";
	private static final String CUSTOM_OBJECTCLASS_PREFIX = "Custom";
	private static final String CUSTOM_OBJECTCLASS_SUFFIX = "ObjectClass";

	private static final Trace LOGGER = TraceManager
	.getTrace(ConnectorInstanceIcfImpl.class);
	
	ConnectorFacade connector;
	ResourceType resource;

	public ConnectorInstanceIcfImpl(ConnectorFacade connector, ResourceType resource) {
		this.connector = connector;
		this.resource = resource;
	}

	private String getSchemaNamespace() {
		return resource.getNamespace();
	}

	/**
	 * Retrieves schema from the resource.
	 * 
	 * Transforms native ICF schema to the midPoint representation.
	 * 
	 * @return midPoint resource schema.
	 * @throws CommunicationException
	 */
	@Override
	public Schema fetchResourceSchema(OperationResult parentResult) throws CommunicationException,
			GenericFrameworkException {

		// Result type for this operation
		OperationResult result = parentResult.createSubresult(ConnectorInstance.class.getName()
				+ ".fetchResourceSchema");
		result.addContext("resource", resource);

		// Connector operation cannot create result for itself, so we need to
		// create result for it
		OperationResult icfResult = result.createSubresult(ConnectorFacade.class.getName() + ".schema");
		icfResult.addContext("connector", connector.getClass());

		org.identityconnectors.framework.common.objects.Schema icfSchema = null;
		try {

			// Fetch the schema from the connector (which actually gets that
			// from the resource).
			icfSchema = connector.schema();

			icfResult.recordSuccess();
		} catch (Exception ex) {
			// ICF interface does not specify exceptions or other error
			// conditions.
			// Therefore this kind of heavy artilery is necessary.
			// TODO maybe we can try to catch at least some specific exceptions
			icfResult.recordFatalError(ex);
			result.recordFatalError("ICF invocation failed");
			// This is fatal. No point in continuing.
			throw new GenericFrameworkException(ex);
		}

		// New instance of midPoint schema object
		Schema mpSchema = new Schema(getSchemaNamespace());
		Set<Definition> definitions = mpSchema.getDefinitions();

		// Let's convert every objectclass in the ICF schema ...
		Set<ObjectClassInfo> objectClassInfoSet = icfSchema.getObjectClassInfo();
		for (ObjectClassInfo objectClassInfo : objectClassInfoSet) {

			// "Flat" ICF object class names needs to be mapped to QNames
			QName objectClassXsdName = objectClassToQname(objectClassInfo.getType());

			// Element names does not really make much sense in Resource
			// Objects as they are not usually used. But for the sake of
			// completeness we are going to generate them.
			// TODO: this may need to be moved to a separate method
			QName objectElementName;
			if (ObjectClass.ACCOUNT_NAME.equals(objectClassInfo.getType())) {
				objectElementName = new QName(getSchemaNamespace(), "account",
						SchemaConstants.NS_ICF_SCHEMA_PREFIX);
			} else if (ObjectClass.GROUP_NAME.equals(objectClassInfo.getType())) {
				objectElementName = new QName(getSchemaNamespace(), "group",
						SchemaConstants.NS_ICF_SCHEMA_PREFIX);
			} else {
				objectElementName = new QName(getSchemaNamespace(), objectClassInfo.getType(),
						SchemaConstants.NS_ICF_RESOURCE_INSTANCE_PREFIX);
			}

			// ResourceObjectDefinition is a midPpoint way how to represent an
			// object class.
			// The important thing here is the last "type" parameter
			// (objectClassXsdName). The rest is more-or-less cosmetics.
			ResourceObjectDefinition roDefinition = new ResourceObjectDefinition(mpSchema, objectElementName,
					objectElementName, objectClassXsdName);
			definitions.add(roDefinition);

			// The __ACCOUNT__ objectclass in ICF is a default account
			// objectclass. So mark it appropriately.
			if (ObjectClass.ACCOUNT_NAME.equals(objectClassInfo.getType())) {
				roDefinition.setAccountType(true);
				roDefinition.setDefaultAccountType(true);
			}

			// Every object has UID in ICF, therefore add it right now
			ResourceObjectAttributeDefinition uidDefinition = new ResourceObjectAttributeDefinition(
					roDefinition, SchemaConstants.ICFS_UID, SchemaConstants.ICFS_UID,
					SchemaConstants.XSD_STRING);
			// Make it mandatory
			uidDefinition.setMinOccurs(1);
			uidDefinition.setMaxOccurs(1);
			roDefinition.getDefinitions().add(uidDefinition);
			// Uid is a primary identifier of every object (this is the ICF way)
			roDefinition.getIdentifiers().add(uidDefinition);

			// TODO: may need also other annotations

			// Let's iterate over all attributes in this object class ...
			Set<AttributeInfo> attributeInfoSet = objectClassInfo.getAttributeInfo();
			for (AttributeInfo attributeInfo : attributeInfoSet) {

				QName attrXsdName = convertAttributeNameToQName(attributeInfo.getName());

				QName attrXsdType = null;
				if (GuardedString.class.equals(attributeInfo.getType())) {
					// GuardedString is a special case. It is a ICF-specific
					// type
					// implementing Potemkin-like security. Use a temporary
					// "nonsense" type for now, so this will fail in tests and
					// will be fixed later
					attrXsdType = SchemaConstants.R_PROTECTED_STRING_TYPE;
				} else {
					attrXsdType = XsdTypeConverter.toXsdType(attributeInfo.getType());
				}

				// Create ResourceObjectAttributeDefinition, which is midPoint
				// way how to express attribute schema.
				ResourceObjectAttributeDefinition roaDefinition = new ResourceObjectAttributeDefinition(
						roDefinition, attrXsdName, attrXsdName, attrXsdType);
				roDefinition.getDefinitions().add(roaDefinition);

				// Now we are gooing to process flas such as optional and
				// multi-valued
				Set<Flags> flagsSet = attributeInfo.getFlags();
				// System.out.println(flagsSet);

				roaDefinition.setMinOccurs(0);
				roaDefinition.setMaxOccurs(1);
				for (Flags flags : flagsSet) {
					if (flags == Flags.REQUIRED) {
						roaDefinition.setMinOccurs(1);
					}
					if (flags == Flags.MULTIVALUED) {
						roaDefinition.setMaxOccurs(-1);
					}
				}

				// TODO: process also other flags

			}

		}

		result.recordSuccess();
		return mpSchema;
	}

	@Override
	public ResourceObject fetchObject(ResourceObjectDefinition resourceObjectDefinition,
			Set<ResourceObjectAttribute> identifiers, OperationResult parentResult)
			throws ObjectNotFoundException, CommunicationException, GenericFrameworkException {

		// Result type for this operation
		OperationResult result = parentResult.createSubresult(ConnectorInstance.class.getName()
				+ ".fetchObject");
		result.addParam("resourceObjectDefinition", resourceObjectDefinition);
		result.addParam("identifiers", identifiers);
		result.addContext("resource", resource);

		// Get UID from the set of idetifiers
		Uid uid = getUid(identifiers);
		if (uid == null) {
			throw new IllegalArgumentException(
					"Required attribute UID not found in identification set while attempting to fetch object identified by "
							+ identifiers + " from recource " + resource.getName() + "(OID:"
							+ resource.getOid() + ")");
		}

		ObjectClass icfObjectClass = objectClassToIcf(resourceObjectDefinition.getTypeName());
		if (icfObjectClass == null) {
			throw new IllegalArgumentException("Unable to detemine object class from QName "
					+ resourceObjectDefinition.getTypeName()
					+ " while attempting to fetch object identified by " + identifiers + " from recource "
					+ resource.getName() + "(OID:" + resource.getOid() + ")");
		}

		ConnectorObject co = null;
		try {

			// Invoke the ICF connector
			co = fetchConnectorObject(icfObjectClass, uid, result);

		} catch (CommunicationException ex) {
			result.recordFatalError("ICF invocation failed due to communication problem");
			// This is fatal. No point in continuing. Just re-throw the
			// exception.
			throw ex;
		} catch (GenericFrameworkException ex) {
			result.recordFatalError("ICF invocation failed due to a generic ICF framework problem");
			// This is fatal. No point in continuing. Just re-throw the
			// exception.
			throw ex;
		}

		if (co == null) {
			result.recordFatalError("Object not found");
			throw new ObjectNotFoundException("Object identified by " + identifiers
					+ " was not found on resource " + ObjectTypeUtil.toShortString(resource));
		}

		ResourceObject ro = convertToResourceObject(co, resourceObjectDefinition);

		result.recordSuccess();
		return ro;

	}

	@Override
	public ResourceObject fetchObject(QName objectClass, Set<ResourceObjectAttribute> identifiers,
			OperationResult parentResult) throws ObjectNotFoundException, CommunicationException,
			GenericFrameworkException {

		// Result type for this operation
		OperationResult result = parentResult.createSubresult(ConnectorInstance.class.getName()
				+ ".fetchObject");
		result.addParam("objectClass", objectClass);
		result.addParam("identifiers", identifiers);
		result.addContext("resource", resource);

		// Get UID from the set of idetifiers
		Uid uid = getUid(identifiers);
		if (uid == null) {
			throw new IllegalArgumentException(
					"Required attribute UID not found in identification set while attempting to fetch object identified by "
							+ identifiers + " from recource " + resource.getName() + "(OID:"
							+ resource.getOid() + ")");
		}

		ObjectClass icfObjectClass = objectClassToIcf(objectClass);
		if (icfObjectClass == null) {
			throw new IllegalArgumentException("Unable to detemine object class from QName " + objectClass
					+ " while attempting to fetch object identified by " + identifiers + " from recource "
					+ resource.getName() + "(OID:" + resource.getOid() + ")");
		}

		ConnectorObject co = null;
		try {

			// Invoke the ICF connector
			co = fetchConnectorObject(icfObjectClass, uid, result);

		} catch (CommunicationException ex) {
			result.recordFatalError("ICF invocation failed due to communication problem");
			// This is fatal. No point in continuing. Just re-throw the
			// exception.
			throw ex;
		} catch (GenericFrameworkException ex) {
			result.recordFatalError("ICF invocation failed due to a generic ICF framework problem");
			// This is fatal. No point in continuing. Just re-throw the
			// exception.
			throw ex;
		}

		if (co == null) {
			result.recordFatalError("Object not found");
			throw new ObjectNotFoundException("Object identified by " + identifiers
					+ " was not found on resource " + ObjectTypeUtil.toShortString(resource));
		}

		ResourceObject ro = convertToResourceObject(co, null);

		result.recordSuccess();
		return ro;
	}

	/**
	 * Returns null if nothing is found.
	 */
	private ConnectorObject fetchConnectorObject(ObjectClass icfObjectClass, Uid uid,
			OperationResult parentResult) throws ObjectNotFoundException, CommunicationException,
			GenericFrameworkException {

		// Connector operation cannot create result for itself, so we need to
		// create result for it
		OperationResult icfResult = parentResult.createSubresult(ConnectorFacade.class.getName()
				+ ".getObject");
		icfResult.addParam("objectClass", icfObjectClass);
		icfResult.addParam("uid", uid.getUidValue());
		icfResult.addParam("options", null);
		icfResult.addContext("connector", connector.getClass());

		ConnectorObject co = null;
		try {

			// Invoke the ICF connector
			co = connector.getObject(icfObjectClass, uid, null);

			icfResult.recordSuccess();
			icfResult.setReturnValue(co);
		} catch (Exception ex) {
			// ICF interface does not specify exceptions or other error
			// conditions.
			// Therefore this kind of heavy artilery is necessary.
			// TODO maybe we can try to catch at least some specific exceptions
			icfResult.recordFatalError(ex);
			// This is fatal. No point in continuing.
			throw new GenericFrameworkException(ex);
		}

		return co;
	}

	@Override
	public Set<ResourceObjectAttribute> addObject(ResourceObject object, Set<Operation> additionalOperations,
			OperationResult parentResult) throws CommunicationException, GenericFrameworkException, SchemaException, ObjectAlreadyExistsException {

		OperationResult result = parentResult.createSubresult(ConnectorInstance.class.getName()
				+ ".addObject");
		result.addParam("resourceObject", object);
		result.addParam("additionalOperations", additionalOperations);

		// getting icf object class from resource object class
		ObjectClass objectClass = objectClassToIcf(object.getDefinition().getTypeName());

		if (objectClass == null) {
			result.recordFatalError("Couldn't get icf object class from resource definition.");
			throw new IllegalArgumentException("Couldn't get icf object class from resource definition.");
		}

		// setting ifc attributes from resource object attributes
		Set<Attribute> attributes = convertFromResourceObject(object.getAttributes());

		if (attributes == null) {
			result.recordFatalError("Couldn't set attributes for icf.");
			throw new IllegalStateException("Couldn't set attributes for icf.");
		}

		OperationResult icfResult = result.createSubresult(ConnectorFacade.class.getName() + ".create");
		icfResult.addParam("objectClass", objectClass);
		icfResult.addParam("attributes", attributes);
		icfResult.addParam("options", null);
		icfResult.addContext("connector", connector);

		try {
			// CALL THE ICF FRAMEWORK
			Uid uid = connector.create(objectClass, attributes, new OperationOptionsBuilder().build());
			
			ResourceObjectAttribute attribute = setUidAttribute(uid);
			object.getAttributes().add(attribute);
			icfResult.recordSuccess();
			
		} catch (Exception ex) {
			Exception midpointEx = processIcfException(ex, icfResult);
			result.computeStatus();
			
			// Do some kind of acrobatics to do proper throwing of checked exception
			if (midpointEx instanceof ObjectAlreadyExistsException) {
				throw (ObjectAlreadyExistsException)midpointEx;
			} else if (midpointEx instanceof CommunicationException) {
				throw (CommunicationException)midpointEx;
			} else if (midpointEx instanceof GenericFrameworkException) {
				throw (GenericFrameworkException)midpointEx;
			} else if (midpointEx instanceof SchemaException) {
				throw (SchemaException)midpointEx;
			} else if (midpointEx instanceof RuntimeException) {
				throw (RuntimeException)midpointEx;
			} else {
				throw new SystemException("Got unexpected exception: "+ex.getClass().getName(),ex);
			}			
		}

		result.recordSuccess();
		return object.getAttributes();
	}

	@Override
	public void modifyObject(QName objectClass, Set<ResourceObjectAttribute> identifiers,
			Set<Operation> changes, OperationResult parentResult) throws ObjectNotFoundException,
			CommunicationException, GenericFrameworkException, SchemaException {

		OperationResult result = parentResult.createSubresult(ConnectorInstance.class.getName()
				+ ".modifyObject");
		result.addParam("objectClass", objectClass);
		result.addParam("identifiers", identifiers);
		result.addParam("changes", changes);

		ObjectClass objClass = objectClassToIcf(objectClass);
		Uid uid = getUid(identifiers);

		Set<ResourceObjectAttribute> addValues = new HashSet<ResourceObjectAttribute>();
		Set<ResourceObjectAttribute> updateValues = new HashSet<ResourceObjectAttribute>();
		Set<ResourceObjectAttribute> valuesToRemove = new HashSet<ResourceObjectAttribute>();

		for (Operation operation : changes) {
			if (operation instanceof AttributeModificationOperation) {
				AttributeModificationOperation change = (AttributeModificationOperation) operation;
				if (change.getChangeType().equals(PropertyModificationTypeType.add)) {
					Property property = change.getNewAttribute();
					ResourceObjectAttribute addAttribute = new ResourceObjectAttribute(property.getName(),
							property.getDefinition(), property.getValues());
					addValues.add(addAttribute);

				}
				if (change.getChangeType().equals(PropertyModificationTypeType.delete)) {
					Property property = change.getNewAttribute();
					ResourceObjectAttribute deleteAttribute = new ResourceObjectAttribute(property.getName(),
							property.getDefinition(), property.getValues());
					valuesToRemove.add(deleteAttribute);
				}
				if (change.getChangeType().equals(PropertyModificationTypeType.replace)) {
					Property property = change.getNewAttribute();
					ResourceObjectAttribute updateAttribute = new ResourceObjectAttribute(property.getName(),
							property.getDefinition(), property.getValues());
					updateValues.add(updateAttribute);

				}
			}

		}
		
		// Needs three complete try-catch blocks because we need to create icfResult for each operation
		// and handle the faults individually
		OperationResult icfResult = null;
		try {
			if (addValues != null && !addValues.isEmpty()) {
				Set<Attribute> attributes = convertFromResourceObject(addValues);
				OperationOptions options = new OperationOptionsBuilder().build();
				icfResult = result.createSubresult(ConnectorFacade.class.getName() + ".addAttributeValues");
				icfResult.addParam("objectClass", objectClass);
				icfResult.addParam("uid", uid);
				icfResult.addParam("attributes", attributes);
				icfResult.addParam("options", options);
				icfResult.addContext("connector", connector);
				connector.addAttributeValues(objClass, uid, attributes, options);
			}
		} catch (Exception ex) {
			Exception midpointEx = processIcfException(ex,icfResult);
			result.computeStatus();
			// Do some kind of acrobatics to do proper throwing of checked exception
			if (midpointEx instanceof ObjectNotFoundException) {
				throw (ObjectNotFoundException)midpointEx;
			} else if (midpointEx instanceof CommunicationException) {
				throw (CommunicationException)midpointEx;
			} else if (midpointEx instanceof GenericFrameworkException) {
				throw (GenericFrameworkException)midpointEx;
			} else if (midpointEx instanceof SchemaException) {
				throw (SchemaException)midpointEx;
			} else if (midpointEx instanceof RuntimeException) {
				throw (RuntimeException)midpointEx;
			} else {
				throw new SystemException("Got unexpected exception: "+ex.getClass().getName(),ex);
			}
		}
			
		try {
			if (updateValues != null && !updateValues.isEmpty()) {
				Set<Attribute> attributes = convertFromResourceObject(updateValues);
				OperationOptions options = new OperationOptionsBuilder().build();
				icfResult = result.createSubresult(ConnectorFacade.class.getName() + ".update");
				icfResult.addParam("objectClass", objectClass);
				icfResult.addParam("uid", uid);
				icfResult.addParam("attributes", attributes);
				icfResult.addParam("options", options);
				icfResult.addContext("connector", connector);
				connector.update(objClass, uid, attributes, options);
			}
		} catch (Exception ex) {
			Exception midpointEx = processIcfException(ex,icfResult);
			result.computeStatus();
			// Do some kind of acrobatics to do proper throwing of checked exception
			if (midpointEx instanceof ObjectNotFoundException) {
				throw (ObjectNotFoundException)midpointEx;
			} else if (midpointEx instanceof CommunicationException) {
				throw (CommunicationException)midpointEx;
			} else if (midpointEx instanceof GenericFrameworkException) {
				throw (GenericFrameworkException)midpointEx;
			} else if (midpointEx instanceof SchemaException) {
				throw (SchemaException)midpointEx;
			} else if (midpointEx instanceof RuntimeException) {
				throw (RuntimeException)midpointEx;
			} else {
				throw new SystemException("Got unexpected exception: "+ex.getClass().getName(),ex);
			}
		}

		try {
			if (valuesToRemove != null && !valuesToRemove.isEmpty()) {
				Set<Attribute> attributes = convertFromResourceObject(valuesToRemove);
				OperationOptions options = new OperationOptionsBuilder().build();
				icfResult = result.createSubresult(ConnectorFacade.class.getName() + ".update");
				icfResult.addParam("objectClass", objectClass);
				icfResult.addParam("uid", uid);
				icfResult.addParam("attributes", attributes);
				icfResult.addParam("options", options);
				icfResult.addContext("connector", connector);
				connector.removeAttributeValues(objClass, uid, attributes, options);
			}
		} catch (Exception ex) {
			Exception midpointEx = processIcfException(ex,icfResult);
			result.computeStatus();
			// Do some kind of acrobatics to do proper throwing of checked exception
			if (midpointEx instanceof ObjectNotFoundException) {
				throw (ObjectNotFoundException)midpointEx;
			} else if (midpointEx instanceof CommunicationException) {
				throw (CommunicationException)midpointEx;
			} else if (midpointEx instanceof GenericFrameworkException) {
				throw (GenericFrameworkException)midpointEx;
			} else if (midpointEx instanceof SchemaException) {
				throw (SchemaException)midpointEx;
			} else if (midpointEx instanceof RuntimeException) {
				throw (RuntimeException)midpointEx;
			} else {
				throw new SystemException("Got unexpected exception: "+ex.getClass().getName(),ex);
			}
		}

	}

	@Override
	public void deleteObject(QName objectClass, Set<ResourceObjectAttribute> identifiers,
			OperationResult parentResult) throws ObjectNotFoundException, CommunicationException,
			GenericFrameworkException {

		OperationResult result = parentResult.createSubresult(ConnectorInstance.class.getName()
				+ ".deleteObject");
		result.addParam("identifiers", identifiers);

		ObjectClass objClass = objectClassToIcf(objectClass);
		Uid uid = getUid(identifiers);

		connector.delete(objClass, uid, new OperationOptionsBuilder().build());
	}

	@Override
	public Property deserializeToken(Object serializedToken) {
		return createTokenProperty(serializedToken);
	}

	@Override
	public Property fetchCurrentToken(QName objectClass, OperationResult parentResult)
			throws CommunicationException, GenericFrameworkException {

		ObjectClass objClass = objectClassToIcf(objectClass);

		SyncToken syncToken = connector.getLatestSyncToken(objClass);

		Property property = getToken(syncToken);

		return property;
	}

	@Override
	public List<Change> fetchChanges(QName objectClass, Property lastToken, OperationResult parentResult)
			throws CommunicationException, GenericFrameworkException {

		OperationResult subresult = parentResult.createSubresult(ConnectorInstance.class.getName()
				+ ".fetchChanges");
		subresult.addContext("objectClass", objectClass);

		lastToken.getDefinition();

		Object obj = null;
		Document doc = DOMUtil.getDocument();
		List<Element> elements = null;
		try {
			elements = lastToken.serializeToDom(doc);
		} catch (SchemaProcessorException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		for (Element e : elements) {
			obj = XsdTypeConverter.toJavaValue(e, lastToken.getDefinition().getTypeName());
		}

		final Set<SyncDelta> result = new HashSet<SyncDelta>();

		ObjectClass icfObjectClass = objectClassToIcf(objectClass);
		SyncToken syncToken = new SyncToken(obj);
		
		SyncResultsHandler syncHandler = new SyncResultsHandler() {
			
			@Override
			public boolean handle(SyncDelta delta) {
				LOGGER.trace("Detected sync delta: {}", delta);
				LOGGER.trace("Delta {}", delta.getObject().toString());
				return result.add(delta);
				
			}
		};
		

		connector.sync(icfObjectClass, syncToken, syncHandler, new OperationOptionsBuilder().build());
		
		List<Change> changeList = null;
		try {
			Schema schema = fetchResourceSchema(parentResult);
			changeList = getChangesFromSyncDelta(result, schema);
		} catch (SchemaException e) {
			// TODO handle exception
			e.printStackTrace();
		}

		return changeList;
	}

	@Override
	public void test(OperationResult parentResult) {

		OperationResult connectionResult = parentResult
				.createSubresult(ProvisioningService.TEST_CONNECTION_CONNECTOR_CONNECTION_OPERATION);
		connectionResult.addContext(OperationResult.CONTEXT_IMPLEMENTATION_CLASS, ConnectorInstance.class);
		connectionResult.addContext("resource", resource);

		try {
			connector.test();
			connectionResult.recordSuccess();
		} catch (ConnectorSecurityException ex) {
			// Looks like this happens for a wide variety of cases. It has inner
			// exception that tells more
			// about the cause
			Throwable cause = ex.getCause();
			if (cause != null) {
				if (cause instanceof javax.naming.CommunicationException) {
					// This seems to be the usual error. However, it also does
					// not describe the case directly
					// We need to go even deeper
					Throwable subCause = cause.getCause();
					if (subCause != null) {
						if (subCause instanceof UnknownHostException) {
							// Looks like the host is not known.
							connectionResult.recordFatalError(
									"The hostname is not known: " + cause.getMessage(), ex);
						} else {
							connectionResult.recordFatalError("Error communicating with the resource: "
									+ cause.getMessage(), ex);
						}
					} else {
						// No subCase
						connectionResult.recordFatalError(
								"Error communicating with the resource: " + cause.getMessage(), ex);
					}
				} else {
					// Cause is not CommunicationException
					connectionResult.recordFatalError("General error: " + cause.getMessage(), ex);
				}
			} else {
				// No cause
				connectionResult.recordFatalError("General error: " + ex.getMessage(), ex);
			}
		} catch (Exception ex) {
			connectionResult.recordFatalError(ex);
		}
	}

	@Override
	public void search(QName objectClass, final ResourceObjectDefinition definition,
			final ResultHandler handler, OperationResult parentResult) throws CommunicationException,
			GenericFrameworkException {

		// Result type for this operation
		final OperationResult result = parentResult.createSubresult(ConnectorInstance.class.getName()
				+ ".search");
		result.addParam("objectClass", objectClass);
		result.addContext("resource", resource);

		ObjectClass icfObjectClass = objectClassToIcf(objectClass);
		if (objectClass == null) {
			IllegalArgumentException ex = new IllegalArgumentException(
					"Unable to detemine object class from QName " + objectClass
							+ " while attempting to searcg objects in recource " + resource.getName()
							+ "(OID:" + resource.getOid() + ")");
			result.recordFatalError("Unable to detemine object class", ex);
			throw ex;
		}
		// TODO: fetchSchema for resource..needed for converting connector
		// object to the resourceObject

		ResultsHandler icfHandler = new ResultsHandler() {
			@Override
			public boolean handle(ConnectorObject connectorObject) {
				// Convert ICF-specific connector object to a generic
				// ResourceObject
				ResourceObject resourceObject = convertToResourceObject(connectorObject, definition);

				// .. and pass it to the handler
				boolean cont = handler.handle(resourceObject);
				if (!cont) {
					result.recordPartialError("Stopped on request from the handler");
				}
				return cont;
			}
		};

		// Connector operation cannot create result for itself, so we need to
		// create result for it
		OperationResult icfResult = result.createSubresult(ConnectorFacade.class.getName() + ".search");
		icfResult.addParam("objectClass", icfObjectClass);
		icfResult.addContext("connector", connector.getClass());

		try {
			
			connector.search(icfObjectClass, null, icfHandler, null);
			
			icfResult.recordSuccess();
		} catch (Exception ex) {
			// ICF interface does not specify exceptions or other error
			// conditions.
			// Therefore this kind of heavy artilery is necessary.
			// TODO maybe we can try to catch at least some specific exceptions
			icfResult.recordFatalError(ex);
			result.recordFatalError("ICF invocation failed");
			// This is fatal. No point in continuing.
			throw new GenericFrameworkException(ex);
		}

		if (result.isUnknown()) {
			result.recordSuccess();
		}
	}

	// UTILITY METHODS

	private QName convertAttributeNameToQName(String icfAttrName) {
		QName attrXsdName = new QName(getSchemaNamespace(), icfAttrName,
				SchemaConstants.NS_ICF_RESOURCE_INSTANCE_PREFIX);
		// Handle special cases
		if (Name.NAME.equals(icfAttrName)) {
			// this is ICF __NAME__ attribute. It will look ugly in XML and may
			// even cause problems.
			// so convert to something more friendly such as icfs:name
			attrXsdName = SchemaConstants.ICFS_NAME;
		}
		if (PASSWORD_ATTRIBUTE_NAME.equals(icfAttrName)) {
			// Temporary hack. Password should go into credentials, not
			// attributes
			// TODO: fix this
			attrXsdName = SchemaConstants.ICFS_PASSWORD;
		}
		return attrXsdName;
	}

	private String convertAttributeNameToIcf(QName attrQName) {
		// Attribute QNames in the resource instance namespace are converted
		// "as is"
		if (attrQName.getNamespaceURI().equals(getSchemaNamespace())) {
			return attrQName.getLocalPart();
		}

		// Other namespace are special cases

		if (SchemaConstants.ICFS_NAME.equals(attrQName)) {
			return Name.NAME;
		}

		if (SchemaConstants.ICFS_PASSWORD.equals(attrQName)) {
			return PASSWORD_ATTRIBUTE_NAME;
		}

		// No mapping available
		throw new IllegalArgumentException("No mapping from QName " + attrQName + " to an ICF attribute name");
	}

	/**
	 * Maps ICF native objectclass name to a midPoint QName objctclass name.
	 * 
	 * The mapping is "stateless" - it does not keep any mapping database or any
	 * other state. There is a bi-directional mapping algorithm.
	 * 
	 * TODO: mind the special characters in the ICF objectclass names.
	 */
	private QName objectClassToQname(String icfObjectClassString) {
		if (ObjectClass.ACCOUNT_NAME.equals(icfObjectClassString)) {
			return new QName(getSchemaNamespace(), ACCOUNT_OBJECTCLASS_LOCALNAME,
					SchemaConstants.NS_ICF_SCHEMA_PREFIX);
		} else if (ObjectClass.GROUP_NAME.equals(icfObjectClassString)) {
			return new QName(getSchemaNamespace(), GROUP_OBJECTCLASS_LOCALNAME,
					SchemaConstants.NS_ICF_SCHEMA_PREFIX);
		} else {
			return new QName(getSchemaNamespace(), CUSTOM_OBJECTCLASS_PREFIX + icfObjectClassString
					+ CUSTOM_OBJECTCLASS_SUFFIX, SchemaConstants.NS_ICF_RESOURCE_INSTANCE_PREFIX);
		}
	}

	/**
	 * Maps a midPoint QName objctclass to the ICF native objectclass name.
	 * 
	 * The mapping is "stateless" - it does not keep any mapping database or any
	 * other state. There is a bi-directional mapping algorithm.
	 * 
	 * TODO: mind the special characters in the ICF objectclass names.
	 */
	private ObjectClass objectClassToIcf(QName qnameObjectClass) {
		if (!getSchemaNamespace().equals(qnameObjectClass.getNamespaceURI())) {
			throw new IllegalArgumentException("ObjectClass QName " + qnameObjectClass
					+ " is not in the appropriate namespace for resource " + resource.getName() + "(OID:"
					+ resource.getOid() + "), expected: " + getSchemaNamespace());
		}
		String lname = qnameObjectClass.getLocalPart();
		if (ACCOUNT_OBJECTCLASS_LOCALNAME.equals(lname)) {
			return ObjectClass.ACCOUNT;
		} else if (GROUP_OBJECTCLASS_LOCALNAME.equals(lname)) {
			return ObjectClass.GROUP;
		} else if (lname.startsWith(CUSTOM_OBJECTCLASS_PREFIX) && lname.endsWith(CUSTOM_OBJECTCLASS_SUFFIX)) {
			String icfObjectClassName = lname.substring(CUSTOM_OBJECTCLASS_PREFIX.length(), lname.length()
					- CUSTOM_OBJECTCLASS_SUFFIX.length());
			return new ObjectClass(icfObjectClassName);
		} else {
			throw new IllegalArgumentException("Cannot recognize objectclass QName " + qnameObjectClass
					+ " for resource " + resource.getName() + "(OID:" + resource.getOid() + "), expected: "
					+ getSchemaNamespace());
		}
	}

	/**
	 * Looks up ICF Uid identifier in a (potentially multi-valued) set of
	 * identifiers. Handy method to convert midPoint identifier style to an ICF
	 * identifier style.
	 * 
	 * @param identifiers
	 *            midPoint resource object identifiers
	 * @return ICF UID or null
	 */
	private Uid getUid(Set<ResourceObjectAttribute> identifiers) {
		for (ResourceObjectAttribute attr : identifiers) {
			if (attr.getName().equals(SchemaConstants.ICFS_UID)) {
				return new Uid(attr.getValue(String.class));
			}
		}
		return null;
	}

	private ResourceObjectAttribute setUidAttribute(Uid uid) {
		ResourceObjectAttribute uidRoa = new ResourceObjectAttribute(SchemaConstants.ICFS_UID);
		uidRoa.setValue(uid.getUidValue());
		return uidRoa;
	}

	/**
	 * Converts ICF ConnectorObject to the midPoint ResourceObject.
	 * 
	 * All the attributes are mapped using the same way as they are mapped in
	 * the schema (which is actually no mapping at all now).
	 * 
	 * If an optional ResourceObjectDefinition was provided, the resulting
	 * ResourceObject is schema-aware (getDefinition() method works). If no
	 * ResourceObjectDefinition was provided, the object is schema-less. TODO:
	 * this still needs to be implemented.
	 * 
	 * @param co
	 *            ICF ConnectorObject to convert
	 * @param def
	 *            ResourceObjectDefinition (from the schema) or null
	 * @return new mapped ResourceObject instance.
	 */
	private ResourceObject convertToResourceObject(ConnectorObject co, ResourceObjectDefinition def) {

		ResourceObject ro = null;
		if (def != null) {
			ro = def.instantiate();
		} else {
			// We don't know the name here. ObjectClass is a type, not name.
			// Therefore it will not help here even if we would have it.
			ro = new ResourceObject();
		}

		// Uid is always there
		Uid uid = co.getUid();
		// PropertyDefinition propDef = new
		// PropertyDefinition(SchemaConstants.ICFS_NAME,
		// SchemaConstants.XSD_STRING);
		// Property p = propDef.instantiate();
		ResourceObjectAttribute uidRoa = setUidAttribute(uid);
		// p = setUidAttribute(uid);
		ro.getAttributes().add(uidRoa);
		// ro.getProperties().add(p);

		for (Attribute icfAttr : co.getAttributes()) {
			if (icfAttr.getName().equals(Uid.NAME)) {
				// UID is handled specially (see above)
				continue;
			}
			QName qname = convertAttributeNameToQName(icfAttr.getName());

			// QName type =
			// XsdTypeConverter.toXsdType(icfAttr.getValue().get(0).getClass());
			// PropertyDefinition pd = new PropertyDefinition(qname, type);
			// Property roa = pd.instantiate();
			// ResourceObjectAttribute roa = road.instantiate();
			ResourceObjectAttribute roa = new ResourceObjectAttribute(qname);
			List<Object> icfValues = icfAttr.getValue();
			roa.getValues().addAll(icfValues);
			// ro.getProperties().add(roa);
			ro.getAttributes().add(roa);
		}

		return ro;
	}

	private Set<Attribute> convertFromResourceObject(Set<ResourceObjectAttribute> resourceAttributes) {
		Set<Attribute> attributes = new HashSet<Attribute>();

		for (ResourceObjectAttribute attribute : resourceAttributes) {

			String attrName = convertAttributeNameToIcf(attribute.getName());
			Attribute connectorAttribute = AttributeBuilder.build(attrName, attribute.getValues());

			attributes.add(connectorAttribute);
		}
		return attributes;
	}

	private List<Change> getChangesFromSyncDelta(Set<SyncDelta> result, Schema schema) throws SchemaException {
		List<Change> changeList = new ArrayList<Change>();
		
		for (SyncDelta delta : result) {
			ObjectClass objClass = delta.getObject().getObjectClass();
			QName objectClass = objectClassToQname(objClass.getObjectClassValue());
			ResourceObjectDefinition rod = (ResourceObjectDefinition) schema.findContainerDefinitionByType(objectClass);
			ResourceObject resourceObject = convertToResourceObject(delta.getObject(), rod);
			
			if (SyncDeltaType.DELETE.equals(delta.getDeltaType())) {
				
				ObjectChangeDeletionType deletionType = new ObjectChangeDeletionType();
				deletionType.setOid(delta.getUid().getUidValue());
				Change change = new Change(resourceObject.getIdentifiers(), deletionType,
						getToken(delta.getToken()));
				changeList.add(change);
			} else {
				ObjectChangeModificationType modificationChangeType = createModificationChange(delta,
						resourceObject);
				
				Set<Property> identifiers = resourceObject.getIdentifiers();
				Property accountName = resourceObject.findProperty(new QName(resource.getNamespace(), "uid"));
				identifiers.add(accountName);

				Change change = new Change(resourceObject.getIdentifiers(), modificationChangeType,
						getToken(delta.getToken()));
				changeList.add(change);
			}

		}
		return changeList;
	}

	private ObjectChangeModificationType createModificationChange(SyncDelta delta,
			ResourceObject resourceObject) throws SchemaException {
		ObjectChangeModificationType modificationChangeType = new ObjectChangeModificationType();
		ObjectModificationType modificationType = new ObjectModificationType();
		modificationType.setOid(delta.getUid().getUidValue());
		
		for (ResourceObjectAttribute attr : resourceObject.getAttributes()) {
			PropertyModificationType propertyModification = new PropertyModificationType();
			propertyModification.setModificationType(PropertyModificationTypeType.add);
			Document doc = DOMUtil.getDocument();
			List<Element> elements;
			try {
				elements = attr.serializeToDom(doc);
				for (Element e : elements){
					LOGGER.debug("Atribute to modify value: {}", e.getTextContent());
				}
				PropertyModificationType.Value value = new PropertyModificationType.Value();
				value.getAny().addAll(elements);
				propertyModification.setValue(value);
				List<XPathSegment> segments = new ArrayList<XPathSegment>();
                XPathSegment attrSegment = new XPathSegment(SchemaConstants.I_ATTRIBUTES);
                segments.add(attrSegment);
                XPathType t = new XPathType(segments);
                Element xpathElement = t.toElement(SchemaConstants.I_PROPERTY_CONTAINER_REFERENCE_PATH, doc);
				propertyModification.setPath(xpathElement);
				modificationType.getPropertyModification().add(propertyModification);
			} catch (SchemaProcessorException ex) {
				throw new SchemaException(
						"An error occured while serializing resource object properties to DOM. "
								+ ex.getMessage(), ex);
			}

		}
		modificationChangeType.setObjectModification(modificationType);
		return modificationChangeType;
	}

	private Property getToken(SyncToken syncToken) {
		Object object = syncToken.getValue();
		return createTokenProperty(object);
	}
	
	private Property createTokenProperty(Object object){
		QName type = XsdTypeConverter.toXsdType(object.getClass());

		Set<Object> objs = new HashSet<Object>();
		objs.add(object);
		PropertyDefinition propDef = new PropertyDefinition(SchemaConstants.C_TOKEN, type);

		Property property = new Property(SchemaConstants.C_TOKEN, propDef, objs);
		return property;
	}

	/**
	 * Transform ICF exception to something more usable.
	 * 
	 * WARNING: This is black magic. Really. Blame ICF interface design.
	 *  
	 * @param ex exception from the ICF
	 * @param parentResult OperationResult to record failure
	 * @return reasonable midPoint exception
	 */
	private Exception processIcfException(Exception ex, OperationResult parentResult) {
		// Whole exception handling in this case is a black magic.
		// ICF does not define any exceptions and there is no "best practice" how to handle ICF errors
		// Therefore let's just guess what might have happened. That's the best we can do.
		
		// Introspect the inner exceptions and look for known causes
		Exception knownCause = lookForKnownCause(ex,ex,parentResult);
		if (knownCause!=null) {
			return knownCause;
		}
		
		// Otherwise try few obvious things
		if (ex instanceof IllegalArgumentException) {
			// This is most likely missing attribute or similar schema thing
			parentResult.recordFatalError("Schema violation",ex);
			return new SchemaException("Schema violation (most likely): "+ex.getMessage(),ex);
		
		} else if (ex instanceof ConnectorSecurityException) {
			// Note: connection refused is also packed inside ConnectorSecurityException. But that will get addressed by the
			// lookForKnownCause(..) before
			parentResult.recordFatalError("Security violation: "+ex.getMessage(),ex);
			// Maybe we need special exception for security?
			return new SystemException("Security violation: "+ex.getMessage(),ex);
		}
		// Fallback
		parentResult.recordFatalError(ex);
		return new GenericFrameworkException(ex);
	}

	private Exception lookForKnownCause(Throwable ex, Throwable originalException,OperationResult parentResult) {
		if (ex instanceof NameAlreadyBoundException) {
			// This is thrown by LDAP connector and may be also throw by similar connectors
			parentResult.recordFatalError("Object already exists",ex);
			return new ObjectAlreadyExistsException(ex.getMessage(),originalException);
		} else if (ex instanceof SchemaViolationException) {
			// This is thrown by LDAP connector and may be also throw by similar connectors
			parentResult.recordFatalError("Schema violation",ex);
			return new SchemaException(ex.getMessage(),originalException);
		} else if (ex instanceof ConnectException) {
			// Buried deep in many exceptions, usually connection refused or similar errors
			parentResult.recordFatalError("Connect error: "+ex.getMessage(),ex);
			return new CommunicationException("Connect error: "+ex.getMessage(),ex);
		}
		if (ex.getCause()==null) {
			// found nothing
			return null;
		} else {
			// Otherwise go one level deeper ...
			return lookForKnownCause(ex.getCause(),originalException,parentResult);
		}
	}
	
}