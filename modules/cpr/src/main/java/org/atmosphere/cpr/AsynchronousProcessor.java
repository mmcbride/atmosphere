/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */

package org.atmosphere.cpr;

import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereHandlerWrapper;
import org.atmosphere.util.LoggerUtils;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class which implement the semantics of suspending and resuming of a
 * Comet Request.
 *
 * @author Jeanfrancois Arcand
 */
abstract public class AsynchronousProcessor implements CometSupport<AtmosphereResourceImpl> {

    protected final static Action timedoutAction = new Action(Action.TYPE.TIMEOUT);
    protected final static Action cancelledAction = new Action(Action.TYPE.CANCELLED);

    protected final Logger logger = LoggerUtils.getLogger();

    protected final AtmosphereConfig config;

    protected final ConcurrentHashMap<HttpServletRequest, AtmosphereResource<HttpServletRequest,HttpServletResponse>>
            aliveRequests = new ConcurrentHashMap<HttpServletRequest, AtmosphereResource<HttpServletRequest,HttpServletResponse>>();

    private final ScheduledExecutorService closedDetector = Executors.newScheduledThreadPool(1);

    public AsynchronousProcessor(AtmosphereConfig config) {
        this.config = config;
    }

    public void init(ServletConfig sc) throws ServletException {

        String maxInactive = sc.getInitParameter(MAX_INACTIVE) != null ? sc.getInitParameter(MAX_INACTIVE) :
                config.getInitParameter(MAX_INACTIVE);
        if (maxInactive !=  null){
            final long maxInactiveTime = Long.parseLong(maxInactive);
            if (maxInactiveTime <= 0) return;

            closedDetector.scheduleAtFixedRate(new Runnable(){
                public void run(){
                    long time = System.currentTimeMillis();
                    for (HttpServletRequest req: aliveRequests.keySet()){
                        long l = (Long) req.getAttribute(MAX_INACTIVE);
                        if (l != 0 && System.currentTimeMillis() - l > maxInactiveTime){
                            try {
                                req.setAttribute(MAX_INACTIVE, (long)-1);
                                cancelled(req,aliveRequests.get(req).getResponse());
                            } catch (IOException e) {
                            } catch (ServletException e) {
                            }
                        }
                    }
                }
            },0,1, TimeUnit.SECONDS);
        }
    }
                                                              
    /**
     * Is {@link HttpSession} supported
     *
     * @return true if supported
     */
    protected boolean supportSession() {
        return config.isSupportSession();
    }

    /**
     * Return the container's name.
     */
    public String getContainerName() {
        return config.getServletConfig().getServletContext().getServerInfo();
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the suspended
     * method when the first request comes in. The returned value, of type
     * {@link AtmosphereServlet.Action}, tells the proprietary Comet {@link Servlet}
     * to suspended or not the current {@link HttpServletResponse}.
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action suspended(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("(suspend) invoked:\n HttpServletRequest: " + req
                    + "\n HttpServletResponse: " + res);
        }
        return action(req, res);
    }


    /**
     * Invoke the {@link AtmosphereHandler#onRequest} method.
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    Action action(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        if (supportSession()) {
            // Create the session needed to support the Resume
            // operation from disparate requests.
            HttpSession session = req.getSession(true);
            // Do not allow times out.
            session.setMaxInactiveInterval(-1);
        }

        req.setAttribute(AtmosphereServlet.SUPPORT_SESSION, supportSession());

        AtmosphereHandlerWrapper g = map(req);
        AtmosphereResourceImpl re = new AtmosphereResourceImpl(config,
                g.broadcaster, req, res, this);
        req.setAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE, re);
        req.setAttribute(AtmosphereServlet.ATMOSPHERE_HANDLER, g.atmosphereHandler);
        g.atmosphereHandler.onRequest(re);

        // User may have changed it.
        config.mapBroadcasterToAtmosphereHandler(re.getBroadcaster(), g);

        if (re.getAtmosphereResourceEvent().isSuspended()) {
            req.setAttribute(MAX_INACTIVE, System.currentTimeMillis());
            aliveRequests.put(req, re);            
        }
        return re.action();
    }

    /**
     * {@inheritDoc}
     */
    public void action(AtmosphereResourceImpl r) {
        aliveRequests.remove(r.getRequest());
    }

    /**
     * Return the {@link AtmosphereHandler} mapped to the passed servlet-path.
     *
     * @param req the {@link HttpServletResponse}
     * @return the {@link AtmosphereHandler} mapped to the passed servlet-path.
     * @throws javax.servlet.ServletException
     */
    protected AtmosphereHandlerWrapper map(HttpServletRequest req) throws ServletException {
        String path = req.getServletPath();
        if (path == null || path.equals("")) {
            path = "/";
        }

        AtmosphereHandlerWrapper atmosphereHandlerWrapper = config.handlers().get(path);
        if (atmosphereHandlerWrapper == null) {
            // Try the /*
            if (!path.endsWith("/")) {
                path += "/*";
            } else {
                path += "*";
            }
            atmosphereHandlerWrapper = config.handlers().get(path);
            if (atmosphereHandlerWrapper == null) {
                atmosphereHandlerWrapper = config.handlers().get("/*");
                if (atmosphereHandlerWrapper == null) {
                    // Try appending the pathInfo
                    path = req.getServletPath() + req.getPathInfo();
                    atmosphereHandlerWrapper = config.handlers().get(path);
                    if (atmosphereHandlerWrapper == null) {
                        // Last chance
                        if (!path.endsWith("/")) {
                            path += "/*";
                        } else {
                            path += "*";
                        }
                        // Try appending the pathInfo
                        atmosphereHandlerWrapper = config.handlers().get(path);
                        if (atmosphereHandlerWrapper == null) {
                            logger.warning("No AtmosphereHandler maps request for " + path);
                            for (String m : config.handlers().keySet()) {
                                logger.warning("\tAtmosphereHandler registered: " + m);
                            }
                            throw new ServletException("No AtmosphereHandler maps request for " + path);
                        }
                    }
                }
            }
        }
        config.getBroadcasterFactory().add(atmosphereHandlerWrapper.broadcaster,
                atmosphereHandlerWrapper.broadcaster.getID());
        return atmosphereHandlerWrapper;
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the resume
     * method when the Atmosphere's application decide to resume the {@link HttpServletResponse}.
     * The returned value, of type
     * {@link AtmosphereServlet.Action}, tells the proprietary Comet {@link Servlet}
     * to resume (again), suspended or do nothing with the current {@link HttpServletResponse}.
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action resumed(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("(resumed) invoked:\n HttpServletRequest: " + req
                    + "\n HttpServletResponse: " + res);
        }
        return action(req, res);
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the timedout
     * method when the underlying WebServer time out the {@link HttpServletResponse}.
     * The returned value, of type
     * {@link AtmosphereServlet.Action}, tells the proprietary Comet {@link Servlet}
     * to resume (again), suspended or do nothing with the current {@link HttpServletResponse}.
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action timedout(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        AtmosphereResourceImpl re;
        // Something went wrong.
        if (req == null || res == null) {
            logger.warning("Invalid Request/Response: " + req + "/" + res);
            return timedoutAction;
        }

        re = (AtmosphereResourceImpl) req.getAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE);

        if (re != null) {
            re.getAtmosphereResourceEvent().isResumedOnTimeout = true;
            if (re.getRequest().getAttribute(AtmosphereServlet.RESUMED_ON_TIMEOUT) != null) {
                re.getAtmosphereResourceEvent().isResumedOnTimeout =
                        (Boolean) re.getRequest().getAttribute(AtmosphereServlet.RESUMED_ON_TIMEOUT);
            }
            invokeAtmosphereHandler(re);
        }

        return timedoutAction;
    }

    void invokeAtmosphereHandler(AtmosphereResourceImpl r) throws IOException {
        HttpServletRequest req = r.getRequest();
        HttpServletResponse res = r.getResponse();
        String disableOnEvent = r.getAtmosphereConfig().getInitParameter(AtmosphereServlet.DISABLE_ONSTATE_EVENT);

        try{
            if (!r.getResponse().equals(res)) {
                logger.warning("Invalid response: " + res);
            } else if (disableOnEvent == null || !disableOnEvent.equals(String.valueOf(true))) {
                AtmosphereHandler<HttpServletRequest,HttpServletResponse> atmosphereHandler  =
                        (AtmosphereHandler<HttpServletRequest,HttpServletResponse>)
                            req.getAttribute(AtmosphereServlet.ATMOSPHERE_HANDLER);
                atmosphereHandler.onStateChange(r.getAtmosphereResourceEvent());
            } else {
                r.getResponse().flushBuffer();
            }
        } finally {
            try {
                aliveRequests.remove(req);
                r.notifyListeners();
            } finally {
                r.removeEventListeners();
                r.getBroadcaster().removeAtmosphereResource(r);
            }
        }
    }

    /**
     * All proprietary Comet based {@link Servlet} must invoke the cancelled
     * method when the underlying WebServer detect that the client closed
     * the connection.
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @return action the Action operation.
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action cancelled(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        AtmosphereResourceImpl re = null;
        try {
            re = (AtmosphereResourceImpl) req.getAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE);
            if (re != null) {
                re.getAtmosphereResourceEvent().setCancelled(true);
                invokeAtmosphereHandler(re);
                re.setIsInScope(false);                
            }
        } catch (Throwable ex) {
            // Something wrong happenned, ignore the exception
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "", ex);
            }
        }

        return cancelledAction;
    }

    void shutdown() {
        closedDetector.shutdownNow();
        for (AtmosphereResource<HttpServletRequest,HttpServletResponse> r : aliveRequests.values()) {
            try {
                r.resume();
            } catch (Throwable t) {
                // Something wrong happenned, ignore the exception
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "", t);
                }
            }
        }
    }
}
