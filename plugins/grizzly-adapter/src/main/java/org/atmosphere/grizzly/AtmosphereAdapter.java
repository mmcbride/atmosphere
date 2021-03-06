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
package org.atmosphere.grizzly;

import com.sun.grizzly.http.servlet.ServletAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyAdapter;
import org.atmosphere.container.GrizzlyCometSupport;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereServlet;

import javax.servlet.ServletContext;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Simple {@link GrizzlyAdapter} that can be used to embed Atmosphere using
 * Grizzly.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereAdapter extends ServletAdapter {

    private String resourcePackage = null;

    private final AtmosphereServlet as = new AtmosphereServlet() {
        /**
         * Auto detect the underlying Servlet Container we are running on.
         */
        @Override
        protected void autoDetectContainer() {
            setUseStreamForFlushingComments(true);
            cometSupport = new GrizzlyCometSupport(getAtmosphereConfig());
        }

        @Override
        protected void autoDetectAtmosphereHandlers(ServletContext sc, URLClassLoader c)
                throws MalformedURLException, URISyntaxException {

            try {
                Class.forName("org.atmosphere.spade.AtmosphereSpadeLauncher");
            } catch (Exception ex) {
                super.autoDetectAtmosphereHandlers(sc, c);
                return;
            }

            String s = sc.getRealPath(WEB_INF);

            // Weblogic bug
            if (s == null) {
                URL u = sc.getResource(WEB_INF);
                if (u == null) return;
                s = u.getPath();
            }

            File f = new File(s);
            // There is a bug in Grizzly 1.9.18 which doesn't construct the URL properly.
            if (!f.exists()){
            	String ctxPath = sc.getContextPath();
                if (System.getProperty("os.name").toLowerCase().startsWith("win")){
                    ctxPath = ctxPath.replace("/","\\");
                }

                int index = s.indexOf(ctxPath);
                if (index < 1) {
                    index = s.length();
                }
                String trailer = s.substring(0,index);
                f = new File(trailer + sc.getContextPath() + WEB_INF);
            }

            if (f.isDirectory()) {
                getFiles(f);
                for (String className : possibleAtmosphereHandlersCandidate) {
                    try {
                        className = className.replace('\\', '/');
                        className = className.substring(className.indexOf(WEB_INF)
                                + WEB_INF.length(), className.lastIndexOf(".")).replace('/', '.');
                        Class<?> clazz = c.loadClass(className);
                        if(AtmosphereHandler.class.isAssignableFrom(clazz)) {
                            AtmosphereHandler g = (AtmosphereHandler) clazz.newInstance();

                            logger.info("Successfully loaded " + g
                                    + " mapped to context-path " + g.getClass().getSimpleName());
                            addAtmosphereHandler("/" + g.getClass().getSimpleName(),g);
                        }
                    } catch (Throwable t) {
                        logger.finest(className + " is not a AtmosphereHandler");
                    }
                }
            }
        }

    };

    @Override
    public void start() {
        if (resourcePackage != null) {
            addInitParameter("com.sun.jersey.config.property.packages", resourcePackage);
        }
        //addInitParameter("com.sun.jersey.config.property.resourceConfigClass", "com.sun.jersey.api.core.PackagesResourceConfig");
        setServletInstance(as);
        super.start();
    }

    /**
     * Use {@link java.io.OutputStream} when flusing bytes. Default is <tt>true</tt> when
     * the runtime module is used, and false when jersey.
     *
     * @param useStreaming If {@link java.io.OutputStream} sould be used.
     */
    public void setUseStreamForFlushingComments(boolean useStreaming) {
        as.setUseStreamForFlushingComments(useStreaming);
    }

    /**
     * Add an {@link AtmosphereHandler}
     *
     * @param path Path to be added to.
     * @param ah   Handler for path.
     */
    public void addAtmosphereHandler(String path, AtmosphereHandler ah) {
        as.addAtmosphereHandler(path, ah);
    }

    /**
     * Set the location of web application. This maps to Jersey's
     * "com.sun.jersey.config.property.packages" property.
     *
     * @return the resourcePackage
     */
    public String getResourcePackage() {
        return resourcePackage;
    }

    /**
     * Return the location of web application.
     *
     * @param resourcePackage the resourcePackage to set
     */
    public void setResourcePackage(String resourcePackage) {
        this.resourcePackage = resourcePackage;
    }

}
