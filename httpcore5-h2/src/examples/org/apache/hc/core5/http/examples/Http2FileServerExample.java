/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http.examples;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EndpointDetails;
import org.apache.hc.core5.http.ExceptionListener;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpConnection;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.impl.ConnectionListener;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.BasicRequestConsumer;
import org.apache.hc.core5.http.nio.BasicResponseProducer;
import org.apache.hc.core5.http.nio.entity.FileEntityProducer;
import org.apache.hc.core5.http.nio.entity.NoopEntityConsumer;
import org.apache.hc.core5.http.nio.support.RequestConsumerSupplier;
import org.apache.hc.core5.http.nio.support.ResponseHandler;
import org.apache.hc.core5.http.nio.support.ResponseTrigger;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http2.frame.RawFrame;
import org.apache.hc.core5.http2.impl.nio.Http2StreamListener;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.io.ShutdownType;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.apache.hc.core5.util.TimeValue;

/**
 * Example of asynchronous embedded HTTP/2 file server.
 */
public class Http2FileServerExample {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Please specify document root directory");
            System.exit(1);
        }
        // Document root directory
        final File docRoot = new File(args[0]);
        int port = 8080;
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        IOReactorConfig config = IOReactorConfig.custom()
                .setSoTimeout(15, TimeUnit.SECONDS)
                .setTcpNoDelay(true)
                .build();

        final HttpAsyncServer server = H2ServerBootstrap.bootstrap()
                .setIOReactorConfig(config)
                .setExceptionListener(new ExceptionListener() {

                    @Override
                    public void onError(final Exception ex) {
                        if (ex instanceof ConnectionClosedException) {
                            return;
                        }
                        if (ex instanceof SocketTimeoutException) {
                            System.out.println("Timeout");
                        } else if (ex instanceof IOException) {
                            System.out.println("I/O error: " + ex.getMessage());
                        } else {
                            ex.printStackTrace();
                        }
                    }
                })
                .setConnectionListener(new ConnectionListener() {

                    @Override
                    public void onConnect(final HttpConnection connection) {
                        System.out.println(connection + " connected");
                    }

                    @Override
                    public void onError(final HttpConnection connection, final Exception ex) {
                        System.err.println(connection + " error: " + ex.getMessage());
                    }

                    @Override
                    public void onDisconnect(final HttpConnection connection) {
                        System.out.println(connection + " disconnected");
                    }

                })
                .setStreamListener(new Http2StreamListener() {

                    @Override
                    public void onHeaderInput(final HttpConnection connection, final int streamId, final List<? extends Header> headers) {
                        for (int i = 0; i < headers.size(); i++) {
                            System.out.println(connection + " (" + streamId + ") << " + headers.get(i));
                        }
                    }

                    @Override
                    public void onHeaderOutput(final HttpConnection connection, final int streamId, final List<? extends Header> headers) {
                        for (int i = 0; i < headers.size(); i++) {
                            System.out.println(connection + " (" + streamId + ") >> " + headers.get(i));
                        }
                    }

                    @Override
                    public void onFrameInput(final HttpConnection connection, final int streamId, final RawFrame frame) {
                    }

                    @Override
                    public void onFrameOutput(final HttpConnection connection, final int streamId, final RawFrame frame) {
                    }

                    @Override
                    public void onInputFlowControl(final HttpConnection connection, final int streamId, final int delta, final int actualSize) {
                    }

                    @Override
                    public void onOutputFlowControl(final HttpConnection connection, final int streamId, final int delta, final int actualSize) {
                    }

                })
                .register("*", new RequestConsumerSupplier<Message<HttpRequest, Void>>() {

                    @Override
                    public AsyncRequestConsumer<Message<HttpRequest, Void>> get(
                            final HttpRequest request,
                            final HttpContext context) throws HttpException {
                        return new BasicRequestConsumer<>(new NoopEntityConsumer());
                    }

                }, new ResponseHandler<Message<HttpRequest, Void>>() {

                    @Override
                    public void handle(
                            final Message<HttpRequest, Void> message,
                            final ResponseTrigger responseTrigger,
                            final HttpContext context) throws HttpException, IOException {
                        HttpRequest request = message.getHead();
                        URI requestUri;
                        try {
                            requestUri = request.getUri();
                        } catch (URISyntaxException ex) {
                            throw new ProtocolException(ex.getMessage(), ex);
                        }
                        String path = requestUri.getPath();
                        final File file = new File(docRoot, path);
                        if (!file.exists()) {

                            System.out.println("File " + file.getPath() + " not found");
                            responseTrigger.submitResponse(new BasicResponseProducer(
                                    HttpStatus.SC_NOT_FOUND,
                                    "<html><body><h1>File" + file.getPath() +
                                            " not found</h1></body></html>",
                                    ContentType.TEXT_HTML));

                        } else if (!file.canRead() || file.isDirectory()) {

                            System.out.println("Cannot read file " + file.getPath());
                            responseTrigger.submitResponse(new BasicResponseProducer(
                                    HttpStatus.SC_FORBIDDEN,
                                    "<html><body><h1>Access denied</h1></body></html>",
                                    ContentType.TEXT_HTML));

                        } else {

                            final ContentType contentType;
                            final String filename = file.getName().toLowerCase(Locale.ROOT);
                            if (filename.endsWith(".txt")) {
                                contentType = ContentType.TEXT_PLAIN;
                            } else if (filename.endsWith(".html") || filename.endsWith(".htm")) {
                                contentType = ContentType.TEXT_HTML;
                            } else if (filename.endsWith(".xml")) {
                                contentType = ContentType.TEXT_XML;
                            } else {
                                contentType = ContentType.DEFAULT_BINARY;
                            }

                            HttpCoreContext coreContext = HttpCoreContext.adapt(context);
                            EndpointDetails endpoint = coreContext.getEndpointDetails();
                            System.out.println(endpoint + ": serving file " + file.getPath());
                            responseTrigger.submitResponse(new BasicResponseProducer(
                                    HttpStatus.SC_OK, new FileEntityProducer(file, contentType)));
                        }
                    }

                })
                .create();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("HTTP server shutting down");
                server.shutdown(ShutdownType.GRACEFUL);
            }
        });

        server.start();
        ListenerEndpoint listenerEndpoint = server.listen(new InetSocketAddress(port));
        listenerEndpoint.waitFor();
        System.out.print("Listening on " + listenerEndpoint.getAddress());
        server.awaitShutdown(TimeValue.MAX_VALUE);
    }

}