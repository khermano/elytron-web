/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.elytron.web.undertow.server.servlet;

import static io.undertow.util.StatusCodes.INTERNAL_SERVER_ERROR;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.config.AuthConfigFactory;
import javax.security.auth.message.config.AuthConfigProvider;
import javax.security.auth.message.config.ServerAuthConfig;
import javax.security.auth.message.config.ServerAuthContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.jboss.logging.Logger;
import org.wildfly.elytron.web.undertow.server.SecurityContextImpl;
import org.wildfly.security.auth.jaspi.impl.JaspiAuthenticationContext;
import org.wildfly.security.auth.jaspi.impl.ServletMessageInfo;
import org.wildfly.security.auth.server.SecurityIdentity;

import io.undertow.security.api.SecurityContext;

/**
 * An extension of {@link SecurityContextImpl} to add JASPIC / Servlet Profile Support.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class ServletSecurityContextImpl extends SecurityContextImpl {

    private static final Logger log = Logger.getLogger("org.wildfly.security.http.servlet");

    private static final String AUTH_TYPE = "javax.servlet.http.authType";
    private static final String MANDATORY = "javax.security.auth.message.MessagePolicy.isMandatory";
    private static final String REGISTER_SESSION = "javax.servlet.http.registerSession";

    private static final String SERVLET_MESSAGE_LAYER = "HttpServlet";
    private static final String IDENTITY_KEY = IdentityContainer.class.getName();

    private final boolean enableJaspi;
    private final boolean integratedJaspi;
    private final String applicationContext;
    private final HttpServletRequest httpServletRequest;
    private final HttpServletResponse httpServletResponse;

    /*
     * Although added for JASPIC if any other servlet specific behaviour is required it can be overridden here.
     */

    ServletSecurityContextImpl(Builder builder) {
        super(builder);

        this.enableJaspi = builder.enableJaspi;
        this.integratedJaspi = builder.integratedJaspi;
        this.applicationContext = builder.applicationContext;
        this.httpServletRequest = builder.httpServletRequest;
        this.httpServletResponse = builder.httpServletResponse;
        log.tracef("Created ServletSecurityContextImpl enableJapi=%b, applicationContext=%s", enableJaspi, applicationContext);
    }

    @Override
    public boolean authenticate() {
        if (isAuthenticated()) {
            return true;
        }

        // If JASPI do JASPI
        if (enableJaspi) {
            AuthConfigFactory authConfigFactory = getAuthConfigFactory();
            if (authConfigFactory != null) {
                AuthConfigProvider configProvider = authConfigFactory.getConfigProvider(SERVLET_MESSAGE_LAYER, applicationContext, null);
                if (configProvider != null) {
                    try {
                        return authenticate(configProvider);
                    } catch (AuthException | SecurityException e) {
                        log.trace("Authentication failed.", e);
                        exchange.setStatusCode(INTERNAL_SERVER_ERROR);

                        return false;
                    }
                } else {
                    log.tracef("No AuthConfigProvider for layer=%s, appContext=%s", SERVLET_MESSAGE_LAYER, applicationContext);
                }
            } else {
                log.trace("No AuthConfigFactory available.");
            }
        }

        log.trace("JASPIC Unavailable, using HTTP authentication.");
        return super.authenticate();
    }

    private static AuthConfigFactory getAuthConfigFactory() {
        try {
            // TODO - PermissionCheck
            return AuthConfigFactory.getFactory();
        } catch (Exception e) {
            // Logged at TRACE as this will be per request.
            log.trace("Unable to get AuthConfigFactory", e);
        }

        return null;
    }

    private boolean authenticate(AuthConfigProvider authConfigProvider) throws AuthException, SecurityException {
        HttpSession session = httpServletRequest.getSession(false);
        if (session != null) {
            IdentityContainer identityContainer = (IdentityContainer) session.getAttribute(IDENTITY_KEY);
            if (identityContainer != null) {
                SecurityIdentity securityIdentity = identityContainer.getSecurityIdentity();
                String authType = identityContainer.getAuthType();
                if (securityIdentity != null) {
                    log.trace("SecurityIdentity restored from HttpSession");
                    authenticationComplete(securityIdentity, authType != null ? authType : getMechanismName(), SERVLET_MESSAGE_LAYER);
                    return true;
                }
            } else {
                session.removeAttribute(IDENTITY_KEY);
            }
        }

        // TODO A lot of the initialisation could have happened in advance if it wasn't for the CallbackHandler, maybe
        // we can use some form of contextual handler associated with the thread and a delegate.
        JaspiAuthenticationContext authenticationContext = JaspiAuthenticationContext.newInstance(securityDomain, SERVLET_MESSAGE_LAYER, integratedJaspi);

        // TODO - PermissionCheck
        ServerAuthConfig serverAuthConfig = authConfigProvider.getServerAuthConfig(SERVLET_MESSAGE_LAYER, applicationContext,
                authenticationContext.createCallbackHandler());

        // This is the stage where it is expected we become per-request.
        MessageInfo messageInfo = new ServletMessageInfo();
        messageInfo.setRequestMessage(httpServletRequest);
        messageInfo.setResponseMessage(httpServletResponse);
        if (isAuthenticationRequired()) {
            messageInfo.getMap().put(MANDATORY, Boolean.TRUE.toString());
        }

        // TODO Should be possible to pass this in somehow.
        final Subject serverSubject = null;

        final String authContextId = serverAuthConfig.getAuthContextID(messageInfo);
        // TODO Configured properties.
        final ServerAuthContext serverAuthContext = serverAuthConfig.getAuthContext(authContextId, null, Collections.emptyMap());

        if (serverAuthContext == null) {
            log.trace("No ServerAuthContext returned, JASPI authentication can not proceed.");
            return false;
        }

        final Subject clientSubject = new Subject();
        AuthStatus authStatus = serverAuthContext.validateRequest(messageInfo, clientSubject, serverSubject);
        log.tracef("ServerAuthContext.validateRequest returned AuthStatus=%s", authStatus);

        Map options = messageInfo.getMap();
        boolean registerSession = options.containsKey(REGISTER_SESSION) && Boolean.parseBoolean(String.valueOf(options.get(REGISTER_SESSION)));
        if ((authStatus == AuthStatus.SUCCESS || (authStatus == AuthStatus.SEND_SUCCESS && registerSession))) {
            String authType = options.containsKey(AUTH_TYPE) ? String.valueOf(options.get(AUTH_TYPE)) : getMechanismName();
            SecurityIdentity securityIdentity = authenticationContext.getAuthorizedIdentity();
            if (registerSession) {
                log.trace("Storing SecurityIdentity in HttpSession");
                session = httpServletRequest.getSession(true);
                session.setAttribute(IDENTITY_KEY, new IdentityContainer(securityIdentity, authType));
            }
            if (authStatus == AuthStatus.SUCCESS) {
                authenticationComplete(securityIdentity, authType, SERVLET_MESSAGE_LAYER);
                // TODO 3.8.3.5 If the request / response objects were wrapped we now need to use them.
                return true;
            }
        }

        return false;

        // TODO We need the secureResponse side of the call as well!.
    }

    static Builder builder() {
        return new Builder();
    }

    static class Builder extends org.wildfly.elytron.web.undertow.server.SecurityContextImpl.Builder {

        private boolean enableJaspi = true;
        private boolean integratedJaspi = true;
        private String applicationContext;
        private HttpServletRequest httpServletRequest;
        private HttpServletResponse httpServletResponse;

        Builder setEnableJaspi(boolean enableJaspi) {
            this.enableJaspi = enableJaspi;

            return this;
        }

        Builder setIntegratedJaspi(boolean integratedJaspi) {
            this.integratedJaspi = integratedJaspi;

            return this;
        }

        Builder setApplicationContext(final String applicationContext) {
            this.applicationContext = applicationContext;

            return this;
        }

        Builder setHttpServletRequest(final HttpServletRequest httpServletRequest) {
            this.httpServletRequest = httpServletRequest;

            return this;
        }

        Builder setHttpServletResponse(final HttpServletResponse httpServletResponse) {
            this.httpServletResponse = httpServletResponse;

            return this;
        }

        @Override
        public SecurityContext build() {
            return new ServletSecurityContextImpl(this);
        }

    }

    static class IdentityContainer implements Serializable {

        private static final long serialVersionUID = 812605442632466511L;

        private volatile SecurityIdentity securityIdentity;
        private volatile String authType;

        IdentityContainer(final SecurityIdentity securityIdentity, final String authType) {
            this.securityIdentity = securityIdentity;
            this.authType = authType;
        }

        SecurityIdentity getSecurityIdentity() {
            return securityIdentity;
        }

        String getAuthType() {
            return authType;
        }

    }
}
