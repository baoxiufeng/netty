/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx.extensions;

import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionTestUtil.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WebSocketServerExtensionHandlerTest {

    WebSocketServerExtensionHandshaker mainHandshakerMock =
            mock(WebSocketServerExtensionHandshaker.class, "mainHandshaker");
    WebSocketServerExtensionHandshaker fallbackHandshakerMock =
            mock(WebSocketServerExtensionHandshaker.class, "fallbackHandshaker");
    WebSocketServerExtension mainExtensionMock =
            mock(WebSocketServerExtension.class, "mainExtension");
    WebSocketServerExtension fallbackExtensionMock =
            mock(WebSocketServerExtension.class, "fallbackExtension");

    @Test
    public void testMainSuccess() {
        // initialize
        when(mainHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("main"))).
                thenReturn(mainExtensionMock);
        when(mainHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("fallback"))).
                thenReturn(null);

        when(fallbackHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("fallback"))).
                thenReturn(fallbackExtensionMock);
        when(fallbackHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("main"))).
                thenReturn(null);

        when(mainExtensionMock.rsv()).thenReturn(WebSocketExtension.RSV1);
        when(mainExtensionMock.newResponseData()).thenReturn(
                new WebSocketExtensionData("main", Collections.emptyMap()));
        when(mainExtensionMock.newExtensionEncoder()).thenReturn(new DummyEncoder());
        when(mainExtensionMock.newExtensionDecoder()).thenReturn(new DummyDecoder());

        when(fallbackExtensionMock.rsv()).thenReturn(WebSocketExtension.RSV1);

        // execute
        WebSocketServerExtensionHandler extensionHandler =
                new WebSocketServerExtensionHandler(mainHandshakerMock, fallbackHandshakerMock);
        EmbeddedChannel ch = new EmbeddedChannel(extensionHandler);

        HttpRequest req = newUpgradeRequest("main, fallback");
        ch.writeInbound(req);

        HttpResponse res = newUpgradeResponse(null);
        ch.writeOutbound(res);

        HttpResponse res2 = ch.readOutbound();
        List<WebSocketExtensionData> resExts = WebSocketExtensionUtil.extractExtensions(
                res2.headers().get(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS));

        // test
        assertNull(ch.pipeline().context(extensionHandler));
        assertEquals(1, resExts.size());
        assertEquals("main", resExts.get(0).name());
        assertTrue(resExts.get(0).parameters().isEmpty());
        assertNotNull(ch.pipeline().get(DummyDecoder.class));
        assertNotNull(ch.pipeline().get(DummyEncoder.class));

        verify(mainHandshakerMock, atLeastOnce()).handshakeExtension(webSocketExtensionDataMatcher("main"));
        verify(mainHandshakerMock, atLeastOnce()).handshakeExtension(webSocketExtensionDataMatcher("fallback"));
        verify(fallbackHandshakerMock, atLeastOnce()).handshakeExtension(webSocketExtensionDataMatcher("fallback"));

        verify(mainExtensionMock, atLeastOnce()).rsv();
        verify(mainExtensionMock).newResponseData();
        verify(mainExtensionMock).newExtensionEncoder();
        verify(mainExtensionMock).newExtensionDecoder();
        verify(fallbackExtensionMock, atLeastOnce()).rsv();
    }

    @Test
    public void testCompatibleExtensionTogetherSuccess() {
        // initialize
        when(mainHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("main"))).
                thenReturn(mainExtensionMock);
        when(mainHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("fallback"))).
                thenReturn(null);

        when(fallbackHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("fallback"))).
                thenReturn(fallbackExtensionMock);
        when(fallbackHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("main"))).
                thenReturn(null);

        when(mainExtensionMock.rsv()).thenReturn(WebSocketExtension.RSV1);
        when(mainExtensionMock.newResponseData()).thenReturn(
                new WebSocketExtensionData("main", Collections.emptyMap()));
        when(mainExtensionMock.newExtensionEncoder()).thenReturn(new DummyEncoder());
        when(mainExtensionMock.newExtensionDecoder()).thenReturn(new DummyDecoder());

        when(fallbackExtensionMock.rsv()).thenReturn(WebSocketExtension.RSV2);
        when(fallbackExtensionMock.newResponseData()).thenReturn(
                new WebSocketExtensionData("fallback", Collections.emptyMap()));
        when(fallbackExtensionMock.newExtensionEncoder()).thenReturn(new Dummy2Encoder());
        when(fallbackExtensionMock.newExtensionDecoder()).thenReturn(new Dummy2Decoder());

        // execute
        WebSocketServerExtensionHandler extensionHandler =
                new WebSocketServerExtensionHandler(mainHandshakerMock, fallbackHandshakerMock);
        EmbeddedChannel ch = new EmbeddedChannel(extensionHandler);

        HttpRequest req = newUpgradeRequest("main, fallback");
        ch.writeInbound(req);

        HttpResponse res = newUpgradeResponse(null);
        ch.writeOutbound(res);

        HttpResponse res2 = ch.readOutbound();
        List<WebSocketExtensionData> resExts = WebSocketExtensionUtil.extractExtensions(
                res2.headers().get(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS));

        // test
        assertNull(ch.pipeline().context(extensionHandler));
        assertEquals(2, resExts.size());
        assertEquals("main", resExts.get(0).name());
        assertEquals("fallback", resExts.get(1).name());
        assertNotNull(ch.pipeline().get(DummyDecoder.class));
        assertNotNull(ch.pipeline().get(DummyEncoder.class));
        assertNotNull(ch.pipeline().get(Dummy2Decoder.class));
        assertNotNull(ch.pipeline().get(Dummy2Encoder.class));

        verify(mainHandshakerMock).handshakeExtension(webSocketExtensionDataMatcher("main"));
        verify(mainHandshakerMock).handshakeExtension(webSocketExtensionDataMatcher("fallback"));
        verify(fallbackHandshakerMock).handshakeExtension(webSocketExtensionDataMatcher("fallback"));
        verify(mainExtensionMock, times(2)).rsv();
        verify(mainExtensionMock).newResponseData();
        verify(mainExtensionMock).newExtensionEncoder();
        verify(mainExtensionMock).newExtensionDecoder();

        verify(fallbackExtensionMock, times(2)).rsv();

        verify(fallbackExtensionMock).newResponseData();
        verify(fallbackExtensionMock).newExtensionEncoder();
        verify(fallbackExtensionMock).newExtensionDecoder();
    }

    @Test
    public void testNoneExtensionMatchingSuccess() {
        // initialize
        when(mainHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("unknown"))).
                thenReturn(null);
        when(mainHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("unknown2"))).
                thenReturn(null);

        when(fallbackHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("unknown"))).
                thenReturn(null);
        when(fallbackHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("unknown2"))).
                thenReturn(null);

        // execute
        WebSocketServerExtensionHandler extensionHandler =
                new WebSocketServerExtensionHandler(mainHandshakerMock, fallbackHandshakerMock);
        EmbeddedChannel ch = new EmbeddedChannel(extensionHandler);

        HttpRequest req = newUpgradeRequest("unknown, unknown2");
        ch.writeInbound(req);

        HttpResponse res = newUpgradeResponse(null);
        ch.writeOutbound(res);

        HttpResponse res2 = ch.readOutbound();

        // test
        assertNull(ch.pipeline().context(extensionHandler));
        assertFalse(res2.headers().contains(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS));

        verify(mainHandshakerMock).handshakeExtension(webSocketExtensionDataMatcher("unknown"));
        verify(mainHandshakerMock).handshakeExtension(webSocketExtensionDataMatcher("unknown2"));

        verify(fallbackHandshakerMock).handshakeExtension(webSocketExtensionDataMatcher("unknown"));
        verify(fallbackHandshakerMock).handshakeExtension(webSocketExtensionDataMatcher("unknown2"));
    }

    @Test
    public void testExtensionHandlerNotRemovedByFailureWritePromise() {
        // initialize
        when(mainHandshakerMock.handshakeExtension(webSocketExtensionDataMatcher("main")))
                .thenReturn(mainExtensionMock);
        when(mainExtensionMock.newResponseData()).thenReturn(
                new WebSocketExtensionData("main", Collections.<String, String>emptyMap()));

        // execute
        WebSocketServerExtensionHandler extensionHandler =
                new WebSocketServerExtensionHandler(mainHandshakerMock);
        EmbeddedChannel ch = new EmbeddedChannel(extensionHandler);

        HttpRequest req = newUpgradeRequest("main");
        ch.writeInbound(req);

        HttpResponse res = newUpgradeResponse(null);
        ChannelPromise failurePromise = ch.newPromise();
        ch.writeOneOutbound(res, failurePromise);
        failurePromise.setFailure(new IOException("Cannot write response"));

        // test
        assertNull(ch.readOutbound());
        assertNotNull(ch.pipeline().context(extensionHandler));
        assertTrue(ch.finish());
    }
}
