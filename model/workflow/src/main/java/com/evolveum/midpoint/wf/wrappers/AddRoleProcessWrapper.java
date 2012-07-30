/*
 * Copyright (c) 2012 Evolveum
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
 *
 * Portions Copyrighted 2012 [name of copyright owner]
 */

package com.evolveum.midpoint.wf.wrappers;

import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.model.api.context.ModelState;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ChangeType;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SystemException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.WfConstants;
import com.evolveum.midpoint.wf.WfHook;
import com.evolveum.midpoint.wf.WfTaskUtil;
import com.evolveum.midpoint.wf.messages.ProcessEvent;
import com.evolveum.midpoint.xml.ns._public.common.common_2.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * @author mederly
 */
@Component
public class AddRoleProcessWrapper implements ProcessWrapper {

    private static final Trace LOGGER = TraceManager.getTrace(AddRoleProcessWrapper.class);

    @Autowired(required = true)
    private RepositoryService repositoryService;

    @Autowired(required = true)
    private WfHook wfHook;

    @Autowired(required = true)
    private WfTaskUtil wfTaskUtil;

    public static final String ROLES_TO_APPROVE = "rolesToApprove";
    public static final String APPROVED_ROLES = "approvedRoles";
    private static final String ADD_ROLE_PROCESS = "addRole";

    @PostConstruct
    public void register() {
        wfHook.registerWfProcessWrapper(this);
    }

    @Override
    public StartProcessInstruction startProcessIfNeeded(ModelContext context, Task task, OperationResult result) {

        if (context.getState() != ModelState.PRIMARY) {
            return null;
        }

        ObjectDelta<? extends ObjectType> change = context.getFocusContext().getPrimaryDelta();

        /*
        * We either add a user; then the list of roles to be added is given by the assignment property,
        * or we modify a user; then the list of roles is given by the assignment property modification.
        */

        List<RoleType> rolesToAdd = new ArrayList<RoleType>();

        if (change.getChangeType() == ChangeType.ADD) {

            PrismObject<?> prismToAdd = change.getObjectToAdd();
            boolean isUser = prismToAdd.getCompileTimeClass().isAssignableFrom(UserType.class);

            if (!isUser) {
                return null;
            }

            UserType user = (UserType) prismToAdd.asObjectable();
            LOGGER.info("Assignments (" + user.getAssignment().size() + "): ");
            for (AssignmentType a : user.getAssignment()) {
                ObjectReferenceType ort = a.getTargetRef();
                LOGGER.info("ort = " + ort);
                LOGGER.info("ort.getType = " + ort.getType());
                if (RoleType.COMPLEX_TYPE.equals(ort.getType())) {
                    RoleType role = resolveRoleRef(a, result);
                    LOGGER.info(" - role: " + role);
                    rolesToAdd.add(role);
                }
            }
        } else if (change.getChangeType() == ChangeType.MODIFY) {

            boolean isUser = change.getObjectTypeClass().isAssignableFrom(UserType.class);

            if (!isUser) {
                return null;
            }

            for (ItemDelta delta : change.getModifications()) {
                if (UserType.F_ASSIGNMENT.equals(delta.getName())) {
                    for (Object o : delta.getValuesToAdd()) {
                        LOGGER.info("Value to add = " + o);
                        PrismContainerValue<AssignmentType> at = (PrismContainerValue<AssignmentType>) o;
                        ObjectReferenceType ort = at.getValue().getTargetRef();
                        LOGGER.info("ort = " + ort);
                        LOGGER.info("ort.getType = " + ort.getType());
                        if (RoleType.COMPLEX_TYPE.equals(ort.getType())) {
                            RoleType role = resolveRoleRef(at.getValue(), result);
                            LOGGER.info(" - role: " + role);
                            rolesToAdd.add(role);
                        }
                    }
                }
            }
        } else {
            return null;            // MODIFY does not need role approval
        }

        if (!rolesToAdd.isEmpty()) {

            UserType oldUser = (UserType) context.getFocusContext().getObjectOld().asObjectable();
            UserType newUser = (UserType) context.getFocusContext().getObjectNew().asObjectable();

            StartProcessInstruction spi = new StartProcessInstruction();
            spi.setProcessName(ADD_ROLE_PROCESS);
            spi.setSimple(true);
            spi.setTaskName("Workflow for approving adding " + rolesToAdd.size() + " role(s) to " + newUser.getName());
            spi.addProcessVariable(WfConstants.MIDPOINT_OBJECT_OLD, oldUser);
            spi.addProcessVariable(WfConstants.MIDPOINT_OBJECT_NEW, newUser);
            spi.addProcessVariable(WfConstants.MIDPOINT_DELTA, change);
            spi.addProcessVariable(ROLES_TO_APPROVE, rolesToAdd);
            return spi;
        }
        return null;
    }


    private RoleType resolveRoleRef(AssignmentType a, OperationResult result) {
        RoleType role = (RoleType) a.getTarget();
        if (role == null) {
            try {
                role = repositoryService.getObject(RoleType.class, a.getTargetRef().getOid(), result).asObjectable();
            } catch (ObjectNotFoundException e) {
                throw new SystemException(e);
            } catch (SchemaException e) {
                throw new SystemException(e);
            }
            a.setTarget(role);
        }
        return role;
    }

    @Override
    public void finishProcess(ProcessEvent event, Task task, OperationResult result) {

        if (event.getAnswer() == Boolean.TRUE) {
            //wfTaskUtil.markAcceptation(task, result);
        } else {
            //wfTaskUtil.markRejection(task, result);
        }

    }
}
