import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.shaded.futures.CompletableFutures;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MaxStreamViolatedTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/resource", (ctx, req) -> HttpResponse.of("hello, world!"))
              .http2MaxStreamsPerConnection(10L);
        }
    };

    static CompletableFuture<List<AggregatedHttpResponse>> getResource(WebClient client, int times) {
        final List<CompletableFuture<AggregatedHttpResponse>> futures = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            futures.add(client.get("/resource").aggregate());
        }
        return CompletableFutures.allAsList(futures);
    }

    @Test
    void maxStreamViolatedScenario() throws Exception {
        final WebClient c1 = WebClient.of(server.httpUri());
        final WebClient c2 = WebClient.of(server.httpUri());

        final AtomicReference<CompletableFuture> futureRef = new AtomicReference<>();
        c1.get("/resource").aggregate().thenRun(() -> futureRef.set(getResource(c2, /* times */ 100))).join();

        Thread.sleep(1000L);

        futureRef.get().join(); // should throw exception for max stream number being violated.
    }

    @Test
    void identicalToAboveButChangeCallstack() throws Exception {
        final WebClient c1 = WebClient.of(server.httpUri());
        final WebClient c2 = WebClient.of(server.httpUri());

        final AtomicReference<CompletableFuture> futureRef = new AtomicReference<>();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            c1.get("/resource").aggregate()
              .thenRunAsync(() -> { // use ...Async callback to change callstack.
                  futureRef.set(getResource(c2, /* times */ 100));
              }, captor.get().eventLoop()).join();
        }

        Thread.sleep(1000L);

        futureRef.get().join(); // no exception is thrown.
    }
}
