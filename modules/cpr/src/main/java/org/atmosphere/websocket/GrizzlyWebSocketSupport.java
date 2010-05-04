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
package org.atmosphere.websocket;

import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;
import com.sun.grizzly.websockets.BaseServerWebSocket;
import com.sun.grizzly.websockets.DataFrame;
import com.sun.grizzly.websockets.WebSocket;
import com.sun.grizzly.websockets.WebSocketApplication;
import org.atmosphere.cpr.WebSocketProcessor;
import org.atmosphere.util.LoggerUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Grizzly support for WebSocket.
 */
public class GrizzlyWebSocketSupport extends WebSocketApplication implements WebSocketSupport {

    private WebSocket webSocket;
    private final WebSocketProcessor webSocketProcessor;
    private final HttpServletRequest httpServletRequest;

    public GrizzlyWebSocketSupport(WebSocketProcessor webSocketProcessor, HttpServletRequest httpServletRequest) {
        this.webSocketProcessor = webSocketProcessor;
        this.httpServletRequest = httpServletRequest;
    }

    @Override
    public WebSocket createSocket(Request request, Response response)
            throws IOException {

        webSocket = new BaseServerWebSocket(this, request, response);
        return webSocket;
    }

    public void writeError(int errorCode, String message) throws IOException {
    }

    public void redirect(String location) throws IOException {
    }

    public void write(byte frame, String data) throws IOException {
        webSocket.send(data);
    }

    public void write(byte frame, byte[] data) throws IOException {
        webSocket.send(new String(data));
    }

    public void write(byte frame, byte[] data, int offset, int length) throws IOException {
        webSocket.send(new String(data, offset, length));
    }

    public void close() throws IOException {
        webSocket.close();
    }

    public void onConnect(WebSocket webSocket) {
        try {
            webSocketProcessor.connect(httpServletRequest);
        } catch (IOException e) {
            LoggerUtils.getLogger().log(Level.WARNING, "", e);
        }
    }

    public void onMessage(WebSocket webSocket, DataFrame dataFrame) {
        webSocketProcessor.broadcast((byte) 0x00, dataFrame.getTextPayload());
    }

    public void onClose(WebSocket webSocket) {
        webSocketProcessor.close();
    }

}