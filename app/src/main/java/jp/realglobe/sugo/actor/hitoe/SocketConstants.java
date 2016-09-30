package jp.realglobe.sugo.actor.hitoe;

/**
 * Created by fukuchidaisuke on 16/09/30.
 */
public final class SocketConstants {

    private SocketConstants() {
    }

    static final class GreetingEvents {
        private GreetingEvents() {
        }

        static final String HI = "sg:greet:hi";
    }

    static final class RemoteEvents {

        private RemoteEvents() {
        }

        static final String SPEC = "sg:remote:spec";
        static final String PIPE = "sg:remote:pipe";
    }

}
