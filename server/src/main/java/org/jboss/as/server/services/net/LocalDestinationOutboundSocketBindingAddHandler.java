/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server.services.net;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.server.services.net.LocalDestinationOutboundSocketBindingResourceDefinition.SOCKET_BINDING_CAPABILITY_NAME;
import static org.jboss.as.server.services.net.OutboundSocketBindingResourceDefinition.OUTBOUND_SOCKET_BINDING_CAPABILITY;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.CapabilityServiceBuilder;
import org.jboss.as.controller.CapabilityServiceTarget;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationContext.Stage;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.parsing.Element;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.network.NetworkInterfaceBinding;
import org.jboss.as.network.OutboundSocketBinding;
import org.jboss.as.network.SocketBinding;
import org.jboss.as.network.SocketBindingManager;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;

/**
 * Handles "add" operation for a local-destination outbound-socket-binding
 *
 * @author Jaikiran Pai
 */
public class LocalDestinationOutboundSocketBindingAddHandler extends AbstractAddStepHandler {

    static final LocalDestinationOutboundSocketBindingAddHandler INSTANCE = new LocalDestinationOutboundSocketBindingAddHandler();

    private LocalDestinationOutboundSocketBindingAddHandler() {
        super(LocalDestinationOutboundSocketBindingResourceDefinition.OUTBOUND_SOCKET_BINDING_CAPABILITY, LocalDestinationOutboundSocketBindingResourceDefinition.ATTRIBUTES);
    }

    @Override
    protected boolean requiresRuntime(OperationContext context) {
        if (context.getProcessType().isServer()) {
            return super.requiresRuntime(context);
        }
        //Check if we are a host's socket binding and install the service if we are
        PathAddress pathAddress = context.getCurrentAddress();
        return pathAddress.size() > 0 && pathAddress.getElement(0).getKey().equals(HOST);
    }

    @Override
    protected void populateModel(final OperationContext context, final ModelNode operation, final Resource resource)
            throws OperationFailedException {
        final PathAddress address = PathAddress.pathAddress(operation.get(OP_ADDR));
        final String socketBindingGroupName = address.getParent().getLastElement().getValue();
        final String outboundSocketBindingName = address.getLastElement().getValue();

        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                Resource resource;
                if (!context.getProcessType().isServer()) {
                    try {
                        resource = context.readResourceFromRoot(context.getCurrentAddress().getParent(), false);
                        validation(socketBindingGroupName, outboundSocketBindingName, resource, true, new ArrayList<String>());
                    } catch (Resource.NoSuchResourceException e) {
                        // this occurs in the case of an ignored server-group being added to a slave.
                        // for all other cases, the parent element is always present.
                        return;
                    }
                }else{
                    resource = context.readResourceFromRoot(PathAddress.pathAddress(ModelDescriptionConstants.SOCKET_BINDING_GROUP, socketBindingGroupName), false);
                    validation(socketBindingGroupName, outboundSocketBindingName, resource, false, new ArrayList<String>());
                }
            }

            private void validation(final String socketBindingGroupName, final String outboundSocketBindingName, final Resource resource, final boolean recursive, List<String> validatedGroupList) {
                Set<String> socketBindingNames = resource.getChildrenNames(ModelDescriptionConstants.SOCKET_BINDING);
                Set<String> remoteDestinationOutboundSocketBindingNames = resource.getChildrenNames(ModelDescriptionConstants.REMOTE_DESTINATION_OUTBOUND_SOCKET_BINDING);
                if(socketBindingNames.contains(outboundSocketBindingName) || remoteDestinationOutboundSocketBindingNames.contains(outboundSocketBindingName)){
                    throw ControllerLogger.ROOT_LOGGER.socketBindingalreadyDeclared(Element.SOCKET_BINDING.getLocalName(),
                            Element.OUTBOUND_SOCKET_BINDING.getLocalName(), outboundSocketBindingName,
                            Element.SOCKET_BINDING_GROUP.getLocalName(), socketBindingGroupName);
                }
                validatedGroupList.add(socketBindingGroupName);

                if (recursive && resource.getModel().hasDefined(ModelDescriptionConstants.INCLUDES)) {
                    List<ModelNode> includedSocketBindingGroups = resource.getModel().get(ModelDescriptionConstants.INCLUDES).asList();
                    for(ModelNode includedSocketBindingGroup : includedSocketBindingGroups){
                        String includedSocketBindingGroupName = includedSocketBindingGroup.asString();
                        if (!validatedGroupList.contains(includedSocketBindingGroupName)) {
                            Resource includedResource = context.readResourceFromRoot(PathAddress.pathAddress(ModelDescriptionConstants.SOCKET_BINDING_GROUP, includedSocketBindingGroupName), false);
                            validation(includedSocketBindingGroupName, outboundSocketBindingName, includedResource, recursive, validatedGroupList);
                        }
                    }
                }
            }

        }, Stage.MODEL);

        super.populateModel(context, operation, resource);
    }

    @Override
    protected void performRuntime(final OperationContext context, final ModelNode operation, final ModelNode model) throws OperationFailedException {

        installOutboundSocketBindingService(context, model);
    }

    static void installOutboundSocketBindingService(final OperationContext context, final ModelNode model) throws OperationFailedException {
        final CapabilityServiceTarget serviceTarget = context.getCapabilityServiceTarget();

        // socket name
        final String outboundSocketName = context.getCurrentAddressValue();
        final String socketBindingRef = LocalDestinationOutboundSocketBindingResourceDefinition.SOCKET_BINDING_REF.resolveModelAttribute(context, model).asString();
        // (optional) source interface
        final String sourceInterfaceName = OutboundSocketBindingResourceDefinition.SOURCE_INTERFACE.resolveModelAttribute(context, model).asStringOrNull();
        // (optional) source port
        final Integer sourcePort = OutboundSocketBindingResourceDefinition.SOURCE_PORT.resolveModelAttribute(context, model).asIntOrNull();
        // (optional but defaulted) fixedSourcePort
        final boolean fixedSourcePort = OutboundSocketBindingResourceDefinition.FIXED_SOURCE_PORT.resolveModelAttribute(context, model).asBoolean();

        // create the service
        final LocalDestinationOutboundSocketBindingService outboundSocketBindingService = new LocalDestinationOutboundSocketBindingService(outboundSocketName, sourcePort, fixedSourcePort);
        final CapabilityServiceBuilder<OutboundSocketBinding> serviceBuilder = serviceTarget.addCapability(OUTBOUND_SOCKET_BINDING_CAPABILITY, outboundSocketBindingService);
        // if a source interface has been specified then add a dependency on it
        if (sourceInterfaceName != null) {
            serviceBuilder.addDependency(NetworkInterfaceService.JBOSS_NETWORK_INTERFACE.append(sourceInterfaceName), NetworkInterfaceBinding.class, outboundSocketBindingService.getSourceNetworkInterfaceBindingInjector());
        }
        serviceBuilder.addCapabilityRequirement(SOCKET_BINDING_CAPABILITY_NAME,
                    SocketBinding.class, outboundSocketBindingService.getLocalDestinationSocketBindingInjector(), socketBindingRef)
                .addCapabilityRequirement("org.wildfly.management.socket-binding-manager", SocketBindingManager.class, outboundSocketBindingService.getSocketBindingManagerInjector())
                .setInitialMode(ServiceController.Mode.ON_DEMAND)
                .addAliases(OutboundSocketBinding.OUTBOUND_SOCKET_BINDING_BASE_SERVICE_NAME.append(outboundSocketName))
                .install();
    }
}
