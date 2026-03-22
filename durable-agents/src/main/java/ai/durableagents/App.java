package ai.durableagents;

import ai.durableagents.restate.ClaimProcessor;
import dev.restate.sdk.endpoint.Endpoint;
import dev.restate.sdk.http.vertx.RestateHttpServer;

public class App {

    public static void main(String[] args) {
        RestateHttpServer.listen(Endpoint.bind(new ClaimProcessor()), 9080);
    }
}
