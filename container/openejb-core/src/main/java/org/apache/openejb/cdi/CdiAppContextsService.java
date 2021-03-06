
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openejb.cdi;

import org.apache.openejb.core.Operation;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.webbeans.annotation.DestroyedLiteral;
import org.apache.webbeans.annotation.InitializedLiteral;
import org.apache.webbeans.config.OWBLogConst;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.context.AbstractContextsService;
import org.apache.webbeans.context.ApplicationContext;
import org.apache.webbeans.context.ConversationContext;
import org.apache.webbeans.context.DependentContext;
import org.apache.webbeans.context.SessionContext;
import org.apache.webbeans.context.SingletonContext;
import org.apache.webbeans.conversation.ConversationImpl;
import org.apache.webbeans.conversation.ConversationManager;
import org.apache.webbeans.el.ELContextStore;
import org.apache.webbeans.event.EventMetadataImpl;
import org.apache.webbeans.spi.ContextsService;
import org.apache.webbeans.spi.ConversationService;
import org.apache.webbeans.web.context.ServletRequestContext;
import org.apache.webbeans.web.intercept.RequestScopedBeanInterceptorHandler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BusyConversationException;
import javax.enterprise.context.ContextException;
import javax.enterprise.context.Conversation;
import javax.enterprise.context.ConversationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.context.spi.Context;
import javax.inject.Singleton;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletRequestEvent;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

public class CdiAppContextsService extends AbstractContextsService implements ContextsService, ConversationService {
    public static final Object EJB_REQUEST_EVENT = new Object();

    private static final Logger logger = Logger.getInstance(LogCategory.OPENEJB.createChild("cdi"), CdiAppContextsService.class);

    private static final String CID = "cid";

    private final ThreadLocal<ServletRequestContext> requestContext = new ThreadLocal<>();

    private final ThreadLocal<SessionContext> sessionContext = new ThreadLocal<>();
    private final UpdatableSessionContextManager sessionCtxManager = new UpdatableSessionContextManager();

    /**
     * Conversation context manager
     */
    private final ThreadLocal<ConversationContext> conversationContext;

    private final DependentContext dependentContext = new DependentContext();

    private final ApplicationContext applicationContext = new ApplicationContext();

    private final SingletonContext singletonContext = new SingletonContext();

    private final WebBeansContext webBeansContext;

    private final ConversationService conversationService;

    private volatile Object appEvent;

    private final boolean useGetParameter;

    private static final ThreadLocal<Collection<Runnable>> endRequestRunnables = new ThreadLocal<Collection<Runnable>>() {
        @Override
        protected Collection<Runnable> initialValue() {
            return new ArrayList<>();
        }
    };

    private volatile boolean autoConversationCheck = true;

    public CdiAppContextsService(final WebBeansContext wbc) {
        this(wbc, wbc.getOpenWebBeansConfiguration().supportsConversation());
    }

    public CdiAppContextsService(final WebBeansContext wbc, final boolean supportsConversation) {
        if (wbc != null) {
            webBeansContext = wbc;
        } else {
            webBeansContext = WebBeansContext.currentInstance();
        }

        dependentContext.setActive(true);
        if (supportsConversation) {
            conversationService = webBeansContext.getService(ConversationService.class);
            if (conversationService == null) {
                conversationContext = null;
            } else {
                conversationContext = new ThreadLocal<>();
            }
        } else {
            conversationService = null;
            conversationContext = null;
        }
        applicationContext.setActive(true);
        singletonContext.setActive(true);
        useGetParameter = "true".equalsIgnoreCase(SystemInstance.get().getProperty("openejb.cdi.conversation.http.use-get-parameter", "false"));
    }

    private void endRequest() {
        for (final Runnable r : endRequestRunnables.get()) {
            try {
                r.run();
            } catch (final Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
        endRequestRunnables.remove();
    }

    public static void pushRequestReleasable(final Runnable runnable) {
        endRequestRunnables.get().add(runnable);
    }

    @Override
    public String getConversationId() {
        return getHttpParameter(CID);
    }

    @Override
    public String getConversationSessionId() {
        return currentSessionId(false);
    }

    public String currentSessionId(final boolean force) {
        final ServletRequestContext rc = requestContext.get();
        if (rc != null) {
            final HttpServletRequest req = rc.getServletRequest();
            if (req != null) {
                final HttpSession session = req.getSession(force);
                if (session != null) {
                    return session.getId();
                }
            }
        }
        return null;
    }

    @Override // this method is called after the deployment (BeansDeployer) but need beans to be here to get events
    public void init(final Object initializeObject) {
        //Start application context
        startContext(ApplicationScoped.class, initializeObject);

        //Start signelton context
        startContext(Singleton.class, initializeObject);
    }

    public void beforeStop(final Object ignored) {
        {   // trigger @PreDestroy mainly but keep it active until destroy(xxx)
            applicationContext.destroy();
            webBeansContext.getBeanManagerImpl().fireEvent(
                    appEvent,
                    new EventMetadataImpl(null, ServletContext.class.isInstance(appEvent) ? ServletContext.class : Object.class, null, new Annotation[]{DestroyedLiteral.INSTANCE_APPLICATION_SCOPED}, webBeansContext),
                    false);
            applicationContext.setActive(true);

            singletonContext.destroy();
            singletonContext.setActive(true);
        }

        for (final Map.Entry<Conversation, ConversationContext> conversation : webBeansContext.getConversationManager().getAllConversationContexts().entrySet()) {
            conversation.getValue().destroy();
            final String id = conversation.getKey().getId();
            if (id != null) {
                webBeansContext.getBeanManagerImpl().fireEvent(id, DestroyedLiteral.INSTANCE_CONVERSATION_SCOPED);
            }
        }
        for (final SessionContext sc : sessionCtxManager.getContextById().values()) { // ensure to destroy session context in time at shutdown and not with session which can happen later
            final Object event = HttpSessionContextSessionAware.class.isInstance(sc) ? HttpSessionContextSessionAware.class.cast(sc).getSession() : sc;
            if (HttpSession.class.isInstance(event)) {
                final HttpSession httpSession = HttpSession.class.cast(event);
                if (httpSession.getId() != null) { // TODO: think if we add a flag to deactivate this behavior (clustering case??)
                    initSessionContext(httpSession);
                    try {
                        // far to be sexy but we need 1) triggering listeners + 2) destroying it *now*
                        // -> org.jboss.cdi.tck.tests.context.session.listener.shutdown.SessionContextListenerShutdownTest
                        httpSession.invalidate();
                    } finally {
                        destroySessionContext(event);
                    }
                }
            } else {
                destroySessionContext(event);
            }
        }
        sessionCtxManager.getContextById().clear();
    }

    public void destroy(final Object destroyObject) {
        //Destroy application context
        endContext(ApplicationScoped.class, destroyObject);

        //Destroy singleton context
        endContext(Singleton.class, destroyObject);

        removeThreadLocals();
    }

    public void removeThreadLocals() {
        //Remove thread locals
        //for preventing memory leaks
        requestContext.set(null);
        requestContext.remove();
        sessionContext.set(null);
        sessionContext.remove();

        if (null != conversationContext) {
            conversationContext.set(null);
            conversationContext.remove();
        }
    }

    @Override
    public void endContext(final Class<? extends Annotation> scopeType, final Object endParameters) {
        if (supportsContext(scopeType)) {
            if (scopeType.equals(RequestScoped.class)) {
                destroyRequestContext(endParameters);
            } else if (scopeType.equals(SessionScoped.class)) {
                destroySessionContext((HttpSession) endParameters);
            } else if (scopeType.equals(ApplicationScoped.class)) {
                destroyApplicationContext();
            } else if (scopeType.equals(Dependent.class)) { //NOPMD
                // Do nothing
            } else if (scopeType.equals(Singleton.class)) {
                destroySingletonContext();
            } else if (supportsConversation() && scopeType.equals(ConversationScoped.class)) {
                destroyConversationContext(endParameters);
            } else {
                if (logger.isWarningEnabled()) {
                    logger.warning("CDI-OpenWebBeans container in OpenEJB does not support context scope "
                            + scopeType.getSimpleName()
                            + ". Scopes @Dependent, @RequestScoped, @ApplicationScoped and @Singleton are supported scope types");
                }
            }
        }
    }

    @Override
    public Context getCurrentContext(final Class<? extends Annotation> scopeType) {
        if (scopeType.equals(RequestScoped.class)) {
            return getRequestContext(true);
        } else if (scopeType.equals(SessionScoped.class)) {
            return getSessionContext(true);
        } else if (scopeType.equals(ApplicationScoped.class)) {
            return getApplicationContext();
        } else if (supportsConversation() && scopeType.equals(ConversationScoped.class)) {
            return getConversationContext();
        } else if (scopeType.equals(Dependent.class)) {
            return dependentContext;
        } else if (scopeType.equals(Singleton.class)) {
            return getSingletonContext();
        }

        return null;
    }

    @Override
    public void startContext(final Class<? extends Annotation> scopeType, final Object startParameter) throws ContextException {
        if (supportsContext(scopeType)) {
            if (scopeType.equals(RequestScoped.class)) {
                initRequestContext(startParameter);
            } else if (scopeType.equals(SessionScoped.class)) {
                initSessionContext((HttpSession) startParameter);
            } else if (scopeType.equals(ApplicationScoped.class)) {
                initApplicationContext(startParameter);
            } else if (scopeType.equals(Dependent.class)) {
                initSingletonContext();
            } else if (scopeType.equals(Singleton.class)) { //NOPMD
                // Do nothing
            } else if (supportsConversation() && scopeType.equals(ConversationScoped.class) && !isTimeout()) {
                initConversationContext(startParameter);
            } else {
                if (logger.isWarningEnabled()) {
                    logger.warning("CDI-OpenWebBeans container in OpenEJB does not support context scope "
                            + scopeType.getSimpleName()
                            + ". Scopes @Dependent, @RequestScoped, @ApplicationScoped and @Singleton are supported scope types");
                }
            }
        }
    }

    private void initSingletonContext() {
        singletonContext.setActive(true);
    }

    private void initApplicationContext(final Object init) { // in case contexts are stop/start
        if (appEvent == null) { // no need of sync cause of the lifecycle
            Object event = init;
            if (StartupObject.class.isInstance(init)) {
                final StartupObject so = StartupObject.class.cast(init);
                if (so.isFromWebApp()) { // ear webapps
                    event = so.getWebContext().getServletContext();
                } else if (so.getAppInfo().webAppAlone) {
                    event = SystemInstance.get().getComponent(ServletContext.class);
                }
            } else if (ServletContextEvent.class.isInstance(init)) {
                event = ServletContextEvent.class.cast(init).getServletContext();
            }
            appEvent = event != null ? event : applicationContext;
            webBeansContext.getBeanManagerImpl().fireEvent(
                    appEvent,
                    new EventMetadataImpl(null,
                            ServletContext.class.isInstance(appEvent) ? ServletContext.class : Object.class, null, new Annotation[]{InitializedLiteral.INSTANCE_APPLICATION_SCOPED},
                            webBeansContext),
                    false);
        }
    }

    @Override
    public boolean supportsContext(final Class<? extends Annotation> scopeType) {
        return scopeType.equals(RequestScoped.class)
                || scopeType.equals(SessionScoped.class)
                || scopeType.equals(ApplicationScoped.class)
                || scopeType.equals(Dependent.class)
                || scopeType.equals(Singleton.class)
                || scopeType.equals(ConversationScoped.class) && supportsConversation();
    }

    private void initRequestContext(final Object event) {
        final ServletRequestContext rq = new ServletRequestContext();
        rq.setActive(true);

        requestContext.set(rq);// set thread local
        if (event != null && ServletRequestEvent.class.isInstance(event)) {
            final HttpServletRequest request = (HttpServletRequest) ServletRequestEvent.class.cast(event).getServletRequest();
            rq.setServletRequest(request);

            if (request != null) {
                webBeansContext.getBeanManagerImpl().fireEvent(request, InitializedLiteral.INSTANCE_REQUEST_SCOPED);
            }

            if (request != null) {
                //Re-initialize thread local for session
                final HttpSession session = request.getSession(false);

                final String cid = conversationService != null ? (!useGetParameter ? getCid(request) : request.getParameter(CID)) : null;
                if (session != null) {
                    initSessionContext(session);
                    if (autoConversationCheck && conversationService != null && !isConversationSkipped(request)) {
                        if (cid != null) {
                            final ConversationManager conversationManager = webBeansContext.getConversationManager();
                            final ConversationImpl c = conversationManager.getPropogatedConversation(cid, session.getId());
                            if (c != null) {
                                final ConversationContext context = conversationManager.getConversationContext(c);
                                context.setActive(true);
                                conversationContext.set(context);
                                return;
                            }
                        }
                    }
                }

                if (cid == null && !isTimeout() && autoConversationCheck) {
                    // transient but active
                    initConversationContext(request);
                }
            }
        } else if (event == EJB_REQUEST_EVENT) {
            webBeansContext.getBeanManagerImpl().fireEvent(event, InitializedLiteral.INSTANCE_REQUEST_SCOPED);
        }
    }

    public static String getCid(final HttpServletRequest req) {
        return getFromQuery(CID, req.getQueryString());
    }

    public static String getFromQuery(final String name, final String q) {
        final int cid = q == null ? -1 : q.indexOf(name + "=");
        if (cid < 0) {
            return null;
        }
        int end = q.indexOf("&", cid);
        final int end2 = q.indexOf("#", cid);
        if (end2 > 0 && end2 < end) {
            end = end2;
        }
        if (end < 0) {
            end = q.length();
        }
        return q.substring(cid + name.length() + 1, end);
    }

    public boolean isAutoConversationCheck() {
        return autoConversationCheck;
    }

    public void checkConversationState() {
        final ServletRequestContext rc = getRequestContext(false);
        if (rc != null && rc.getServletRequest() != null && conversationService != null) {
            final HttpSession session = rc.getServletRequest().getSession(false);
            if (session != null) {
                final String cid = useGetParameter ? rc.getServletRequest().getParameter(CID) : getFromQuery(CID, rc.getServletRequest().getQueryString());
                if (cid != null) {
                    final ConversationManager conversationManager = webBeansContext.getConversationManager();
                    final ConversationImpl c = conversationManager.getPropogatedConversation(cid, session.getId());
                    if (!autoConversationCheck) { // lazy association
                        initConversationContext(rc.getServletRequest());
                    }
                    if (c != null) {
                        if (c.isTransient()) {
                            throw new IllegalStateException("Conversation " + cid + " missing");
                        }
                        if (c.iUseIt() > 1) {
                            throw new BusyConversationException("busy conversation " + c.getId() + '(' + c.getSessionId() + ')');
                        }
                    }
                }
            }
        }
    }

    private void destroyRequestContext(final Object end) {
        // execute request tasks
        endRequest();

        //Get context
        final ServletRequestContext context = getRequestContext(false);

        //Destroy context
        if (context != null) {
            if (supportsConversation()) { // OWB-595
                cleanupConversation();
            }

            final HttpServletRequest servletRequest = context.getServletRequest();
            if (servletRequest != null) {
                webBeansContext.getBeanManagerImpl().fireEvent(servletRequest, DestroyedLiteral.INSTANCE_REQUEST_SCOPED);
            } else if (end == EJB_REQUEST_EVENT) {
                webBeansContext.getBeanManagerImpl().fireEvent(end, DestroyedLiteral.INSTANCE_REQUEST_SCOPED);
            }
            context.destroy();
        }

        // clean up the EL caches after each request
        final ELContextStore elStore = ELContextStore.getInstance(false);
        if (elStore != null) {
            elStore.destroyELContextStore();
        }

        //Clear thread locals - only for request to let user do with deltaspike start(session, request)restart(request)...stop()
        requestContext.remove();

        RequestScopedBeanInterceptorHandler.removeThreadLocals();
    }

    private void cleanupConversation() {
        if (conversationService == null) {
            return;
        }

        final ConversationContext cc = getConversationContext();
        if (cc == null) {
            return;
        }
        cc.setActive(false);

        final ConversationManager conversationManager = webBeansContext.getConversationManager();
        final Conversation conversation = conversationManager.getConversationBeanReference();
        if (conversation == null) {
            return;
        }

        final ConversationImpl conversationImpl = ConversationImpl.class.cast(conversation);
        conversationImpl.iDontUseItAnymore(); // do it before next call to avoid busy exception if possible
        try {
            if (conversation.isTransient()) {
                endContext(ConversationScoped.class, null);
                conversationManager.removeConversation(conversation); // in case end() was called
            } else {
                conversationImpl.updateTimeOut();
            }
        } catch (final BusyConversationException bce) {
            // no-op, TODO: do something, maybe add internalIsTransient() to avoid to fail here
        }
    }

    /**
     * Creates the session context at the session start.
     *
     * @param session http session object
     */
    private void initSessionContext(final HttpSession session) {
        if (session == null) {
            // no session -> no SessionContext
            return;
        }

        final String sessionId = session.getId();
        //Current context
        SessionContext currentSessionContext = sessionId == null ? null : sessionCtxManager.getSessionContextWithSessionId(sessionId);

        //No current context
        boolean fire = false;
        if (currentSessionContext == null) {
            currentSessionContext = newSessionContext(session);
            sessionCtxManager.addNewSessionContext(sessionId, currentSessionContext);
            fire = true;
        }
        //Activate
        currentSessionContext.setActive(true);

        //Set thread local
        sessionContext.set(currentSessionContext);

        if (fire) {
            webBeansContext.getBeanManagerImpl().fireEvent(session, InitializedLiteral.INSTANCE_SESSION_SCOPED);
        }
    }

    private SessionContext newSessionContext(final HttpSession session) {
        final String classname = SystemInstance.get().getComponent(ThreadSingletonService.class).sessionContextClass();
        if (classname != null) {
            try {
                final Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(classname);
                try {
                    final Constructor<?> constr = clazz.getConstructor(HttpSession.class);
                    return (SessionContext) constr.newInstance(session);
                } catch (final Exception e) {
                    return (SessionContext) clazz.newInstance();
                }
            } catch (final Exception e) {
                logger.error("Can't instantiate " + classname + ", using default session context", e);
            }
        }
        return new HttpSessionContextSessionAware(session);
    }

    /**
     * Destroys the session context and all of its components at the end of the
     * session.
     *
     * @param session http session object
     */
    private void destroySessionContext(final Object session) {
        if (session != null) {
            final SessionContext context = sessionContext.get();

            if (context != null && context.isActive()) {
                final ServletRequestContext servletRequestContext = getRequestContext(false);
                if (servletRequestContext == null || servletRequestContext.getServletRequest() == null) {
                    doDestroySession(context, session);
                } else {
                    pushRequestReleasable(new Runnable() { // call it at the end of the request
                        @Override
                        public void run() {
                            doDestroySession(context, session);
                        }
                    });
                }
            }

            //Clear thread locals
            sessionContext.set(null);
            sessionContext.remove();

            //Remove session from manager
            if (HttpSession.class.isInstance(session)) {
                sessionCtxManager.removeSessionContextWithSessionId(HttpSession.class.cast(session).getId());
            }
        }
    }

    private void doDestroySession(final SessionContext context, final Object event) {
        context.destroy();
        webBeansContext.getBeanManagerImpl().fireEvent(event, DestroyedLiteral.INSTANCE_SESSION_SCOPED);
    }

    //we don't have initApplicationContext

    private void destroyApplicationContext() {
        applicationContext.destroy();
    }

    private void destroySingletonContext() {
        singletonContext.destroy();
    }

    private ConversationContext initConversationContext(final Object request) {
        if (conversationService == null) {
            return null;
        }

        final HttpServletRequest req = HttpServletRequest.class.isInstance(request) ? HttpServletRequest.class.cast(request) : null;
        ConversationContext context = ConversationContext.class.isInstance(request) ? ConversationContext.class.cast(request) : null;
        Object event = null;
        if (context == null) {
            final ConversationContext existingContext = conversationContext.get();
            if (existingContext == null) {
                context = new ConversationContext();
                context.setActive(true);

                if (req != null) {
                    event = req;
                } else {
                    final ServletRequestContext servletRequestContext = getRequestContext(true);
                    event = servletRequestContext != null && servletRequestContext.getServletRequest() != null ? servletRequestContext.getServletRequest() : context;
                }
            } else {
                context = existingContext;
            }
        }
        conversationContext.set(context);
        context.setActive(true);
        if (event != null) {
            webBeansContext.getBeanManagerImpl().fireEvent(event, InitializedLiteral.INSTANCE_CONVERSATION_SCOPED);
        }
        return context;
    }

    /**
     * Destroy conversation context.
     */
    private void destroyConversationContext(final Object destroy) {
        if (conversationService == null) {
            return;
        }

        final ConversationContext context = getConversationContext();
        if (context != null) {
            context.destroy();
            final ServletRequestContext servletRequestContext = getRequestContext(false);
            final Object destroyObject = servletRequestContext != null && servletRequestContext.getServletRequest() != null ?
                    servletRequestContext.getServletRequest() : destroy;
            webBeansContext.getBeanManagerImpl().fireEvent(
                    destroyObject == null ? context : destroyObject, DestroyedLiteral.INSTANCE_CONVERSATION_SCOPED);
        }

        if (null != context) {
            conversationContext.remove();
        }
    }


    public ServletRequestContext getRequestContext(final boolean create) {
        ServletRequestContext context = requestContext.get();
        if (context == null && create) {
            initRequestContext(null);
            return requestContext.get();
        }
        return context;
    }

    private Context getSessionContext(final boolean create) {
        SessionContext context = sessionContext.get();
        if ((context == null || !context.isActive()) && create) {
            lazyStartSessionContext();
            context = sessionContext.get();
            if (context == null) {
                context = new SessionContext();
                context.setActive(true);
                sessionContext.set(context);
            }
        }
        return context;
    }

    /**
     * Gets application context.
     *
     * @return application context
     */
    private ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * Gets singleton context.
     *
     * @return singleton context
     */
    private SingletonContext getSingletonContext() {
        return singletonContext;
    }

    /**
     * Get current conversation ctx.
     *
     * @return conversation context
     */
    private ConversationContext getConversationContext() {
        return conversationContext.get();
    }

    private boolean isConversationSkipped(final HttpServletRequest servletRequest) {
        final String queryString = servletRequest.getQueryString();
        return "none".equals(getFromQuery("conversationPropagation", queryString)) || "true".equals(getFromQuery("nocid", queryString));
    }

    private boolean isTimeout() {
        final ThreadContext tc = ThreadContext.getThreadContext();
        return tc != null && tc.getCurrentOperation() == Operation.TIMEOUT;
    }

    private Context lazyStartSessionContext() {
        final Context webContext = null;
        final Context context = getCurrentContext(RequestScoped.class);
        if (context instanceof ServletRequestContext) {
            final ServletRequestContext requestContext = (ServletRequestContext) context;
            final HttpServletRequest servletRequest = requestContext.getServletRequest();
            if (null != servletRequest) { // this could be null if there is no active request context
                try {
                    final HttpSession currentSession = servletRequest.getSession();
                    initSessionContext(currentSession);
                } catch (final Exception e) {
                    logger.error(OWBLogConst.ERROR_0013, e);
                }
            } else {
                logger.warning("Could NOT lazily initialize session context because NO active request context");
            }
        } else {
            logger.warning("Could NOT lazily initialize session context because of " + context + " RequestContext");
        }

        return webContext;
    }

    public void setAutoConversationCheck(final boolean autoConversationCheck) {
        this.autoConversationCheck = autoConversationCheck;
    }

    private boolean supportsConversation() {
        return conversationContext != null;
    }

    public void updateSessionIdMapping(final String oldId, final String newId) {
        sessionCtxManager.updateSessionIdMapping(oldId, newId);
    }

    public State saveState() {
        return new State(requestContext.get(), sessionContext.get(), conversationContext.get());
    }

    public State restoreState(final State state) {
        final State old = saveState();
        requestContext.set(state.request);
        sessionContext.set(state.session);
        conversationContext.set(state.conversation);
        return old;
    }

    public String getHttpParameter(final String name) {
        final ServletRequestContext req = getRequestContext(false);
        if (req != null && req.getServletRequest() != null) {
            return useGetParameter ? req.getServletRequest().getParameter(name) : getFromQuery(name, req.getServletRequest().getQueryString());
        }
        return null;
    }

    public static class State {
        private final ServletRequestContext request;
        private final SessionContext session;
        private final ConversationContext conversation;

        public State(final ServletRequestContext request, final SessionContext session, final ConversationContext conversation) {
            this.request = request;
            this.session = session;
            this.conversation = conversation;
        }
    }

    public static class HttpSessionContextSessionAware extends SessionContext {
        private final HttpSession session;

        public HttpSessionContextSessionAware(final HttpSession session) {
            this.session = session;
        }

        public HttpSession getSession() {
            return session;
        }
    }
}
