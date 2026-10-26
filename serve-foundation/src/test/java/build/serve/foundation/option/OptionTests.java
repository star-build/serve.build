package build.serve.foundation.option;

import build.base.network.option.Port;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptionTests {

    @Test
    void portOfStoresValue() {
        assertThat(Port.of(8080).get()).isEqualTo(8080);
    }

    @Test
    void listenAddressDefaultIsLoopback() {
        assertThat(ListenAddress.DEFAULT.value()).isEqualTo("127.0.0.1");
    }
}
