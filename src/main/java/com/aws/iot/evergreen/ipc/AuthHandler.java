package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.GenericErrorCodes;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientNotAuthorizedException;
import com.aws.iot.evergreen.ipc.services.common.AuthRequestTypes;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import com.aws.iot.evergreen.util.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import io.netty.channel.ChannelHandlerContext;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.net.SocketAddress;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.common.ResponseHelper.sendResponse;

@AllArgsConstructor
@NoArgsConstructor
public class AuthHandler implements InjectionActions {
    public static final String AUTH_TOKEN_LOOKUP_KEY = "_AUTH_TOKENS";
    public static final String SERVICE_UNIQUE_ID_KEY = "_UID";

    @Inject
    private Configuration config;
    @Inject
    private Log log;
    @Inject
    private IPCRouter router;

    /**
     * Register an auth token for the given service.
     *
     * @param s service to generate an auth token for
     */
    public static void registerAuthToken(EvergreenService s) {
        Topic uid = s.config.createLeafChild(SERVICE_UNIQUE_ID_KEY).setParentNeedsToKnow(false);
        String authToken = Utils.generateRandomString(16).toUpperCase();
        uid.setValue(authToken);
        Topic tokenTopic = s.config.parent.lookup(AUTH_TOKEN_LOOKUP_KEY, authToken);

        // If the auth token was already registered, that's an issue, so we will retry
        // generating a new token in that case
        if (tokenTopic.getOnce() == null) {
            tokenTopic.setValue(s.getName());
        } else {
            registerAuthToken(s);
        }
    }

    /**
     * Authenticate the incoming request and return a RequestContext if successful.
     *
     * @param request       incoming request frame to be validated.
     * @param remoteAddress remote address the client is connected from
     * @return RequestContext containing the server name if validated.
     * @throws IPCClientNotAuthorizedException thrown if not authorized, or any other error happens.
     */
    public ConnectionContext doAuth(FrameReader.Message request, SocketAddress remoteAddress)
            throws IPCClientNotAuthorizedException {
        GeneralRequest<String, AuthRequestTypes> decodedRequest;
        try {
            decodedRequest = IPCUtil.decode(request, new TypeReference<GeneralRequest<String, AuthRequestTypes>>() {
            });
        } catch (IOException e) {
            throw new IPCClientNotAuthorizedException(e.getMessage());
        }

        String authToken = decodedRequest.getRequest();

        // Lookup the provided auth token to associate it with a service (or reject it)
        String serviceName = (String) config.lookup(AUTH_TOKEN_LOOKUP_KEY, authToken).getOnce();

        if (serviceName == null) {
            throw new IPCClientNotAuthorizedException("Auth token not found");
        }

        return new ConnectionContext(serviceName, remoteAddress, router);
    }

    void handleAuth(ChannelHandlerContext ctx, FrameReader.MessageFrame message) throws IOException {
        if (message.destination == BuiltInServiceDestinationCode.AUTH.getValue()) {
            try {
                ConnectionContext context = doAuth(message.message, ctx.channel().remoteAddress());
                ctx.channel().attr(IPCChannelHandler.CONNECTION_CONTEXT_KEY).set(context);
                log.note("Successfully authenticated client", context);
                router.clientConnected(context, ctx.channel());
                sendResponse(new FrameReader.Message(IPCUtil.encode(
                        GeneralResponse.builder().response(context.getServiceName()).error(GenericErrorCodes.Success)
                                .build())), message.requestId, message.destination, ctx, false);
            } catch (Throwable t) {
                log.warn("Error while authenticating client", ctx.channel().remoteAddress(), t);
                sendResponse(new FrameReader.Message(IPCUtil.encode(
                        GeneralResponse.builder().errorMessage("Error while authenticating client")
                                .error(GenericErrorCodes.Unauthorized).build())), message.requestId,
                        message.destination, ctx, true);
            }
        } else {
            log.warn("Wrong destination for first packet from client", ctx.channel().remoteAddress());
            sendResponse(new FrameReader.Message(IPCUtil.encode(
                    GeneralResponse.builder().errorMessage("Error while authenticating client")
                            .error(GenericErrorCodes.Unauthorized).build())), message.requestId, message.destination,
                    ctx, true);
        }
    }
}