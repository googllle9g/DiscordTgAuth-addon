package net.millyland.authdiscord;

import com.neovisionaries.ws.client.WebSocketFactory;
import net.dv8tion.jda.api.JDABuilder;
import net.millyland.auth.api.ProxyConfig;
import net.millyland.auth.api.TgAuthAPI;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Socket;
import java.util.Optional;

/**
 * Chooses the proxy the Discord bot connects through and applies it to JDA (REST through OkHttp, the gateway
 * websocket through nv-websocket-client).
 *
 * <p>{@code proxy.enabled: false} in this addon's config switches the proxy off completely: neither the manual
 * settings nor the proxy of TgAuth are used. When enabled, a manual {@code proxy.host} wins; with an empty host the
 * proxy of TgAuth (its config or an addon that supplies one through the API) is taken, if
 * {@code proxy.use-tgauth-proxy} is true.
 */
final class ProxySupport {

    /** The chosen proxy and a short human description of where it came from. */
    record Choice(ProxyConfig proxy, String source) {
    }

    private ProxySupport() {
    }

    /** @return the proxy to use, or null for a direct connection */
    static Choice resolve(DiscordTgAuthPlugin plugin, TgAuthAPI api) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("proxy.enabled", false)) return null;

        String host = String.valueOf(cfg.getString("proxy.host", "")).trim();
        if (!host.isEmpty()) {
            ProxyConfig.Type type = ProxyConfig.Type.parse(cfg.getString("proxy.type", "SOCKS5"));
            if (type == null) {
                plugin.getLogger().warning("proxy.type must be HTTP, SOCKS4 or SOCKS5 - ignoring the manual proxy.");
            } else {
                try {
                    return new Choice(new ProxyConfig(type, host, cfg.getInt("proxy.port", 1080),
                            cfg.getString("proxy.username", ""), cfg.getString("proxy.password", "")),
                            "proxy.* in this config");
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("The manual proxy in config.yml is invalid (" + e.getMessage()
                            + ") - ignoring it.");
                }
            }
        }

        if (cfg.getBoolean("proxy.use-tgauth-proxy", true) && api != null && api.getApiVersion() >= 5) {
            Optional<ProxyConfig> fromApi = api.getProxyConfig();
            if (fromApi.isPresent()) return new Choice(fromApi.get(), "TgAuth");
        }
        return null;
    }

    /** Puts the proxy into the JDA builder. */
    static void apply(JDABuilder builder, ProxyConfig p) {
        InetSocketAddress address = new InetSocketAddress(p.host(), p.port());
        OkHttpClient.Builder http = new OkHttpClient.Builder();
        WebSocketFactory ws = new WebSocketFactory();

        if (p.type() == ProxyConfig.Type.HTTP) {
            http.proxy(new Proxy(Proxy.Type.HTTP, address));
            ws.getProxySettings().setHost(p.host()).setPort(p.port());
            if (p.hasCredentials()) {
                String pass = p.password() == null ? "" : p.password();
                http.proxyAuthenticator((route, response) -> response.request().newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(p.username(), pass)).build());
                ws.getProxySettings().setId(p.username()).setPassword(pass);
            }
        } else {
            Proxy socks = new Proxy(Proxy.Type.SOCKS, address);
            http.proxy(socks);
            ws.setSocketFactory(new SocksSocketFactory(socks));
            if (p.hasCredentials()) ScopedAuthenticator.add(p);
        }
        builder.setHttpClientBuilder(http).setWebsocketFactory(ws);
    }

    /** Removes the JVM authenticator installed for a SOCKS login (plugin disable). */
    static void release() {
        ScopedAuthenticator.remove();
    }

    /** Creates sockets that connect through a SOCKS proxy (the websocket library asks for an unconnected socket). */
    private static final class SocksSocketFactory extends SocketFactory {
        private final Proxy proxy;

        SocksSocketFactory(Proxy proxy) {
            this.proxy = proxy;
        }

        @Override
        public Socket createSocket() {
            return new Socket(proxy);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket s = createSocket();
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket s = createSocket();
            s.bind(new InetSocketAddress(localHost, localPort));
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket s = createSocket();
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            Socket s = createSocket();
            s.bind(new InetSocketAddress(localAddress, localPort));
            s.connect(new InetSocketAddress(address, port));
            return s;
        }
    }

    /**
     * Answers the JVM's SOCKS authentication request for this one proxy (host + port) and hands every other
     * request to the authenticator that was installed before (for instance TgAuth's own, for its Telegram proxy).
     */
    private static final class ScopedAuthenticator extends Authenticator {
        private static ScopedAuthenticator installed;

        private final Authenticator previous;
        private final ProxyConfig proxy;

        private ScopedAuthenticator(Authenticator previous, ProxyConfig proxy) {
            this.previous = previous;
            this.proxy = proxy;
        }

        static synchronized void add(ProxyConfig proxy) {
            remove();
            installed = new ScopedAuthenticator(Authenticator.getDefault(), proxy);
            Authenticator.setDefault(installed);
        }

        static synchronized void remove() {
            // Only undo it if we are still on top; otherwise a later authenticator chains through us harmlessly.
            if (installed != null && Authenticator.getDefault() == installed) {
                Authenticator.setDefault(installed.previous);
            }
            installed = null;
        }

        private boolean isOurProxy() {
            if (getRequestingPort() != proxy.port()) return false;
            String host = getRequestingHost();
            if (host != null && host.equalsIgnoreCase(proxy.host())) return true;
            InetAddress site = getRequestingSite();
            return site != null && (proxy.host().equalsIgnoreCase(site.getHostAddress())
                    || proxy.host().equalsIgnoreCase(site.getHostName()));
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (installed == this && isOurProxy()) {
                String pass = proxy.password() == null ? "" : proxy.password();
                return new PasswordAuthentication(proxy.username(), pass.toCharArray());
            }
            if (previous != null) {
                return Authenticator.requestPasswordAuthentication(previous, getRequestingHost(), getRequestingSite(),
                        getRequestingPort(), getRequestingProtocol(), getRequestingPrompt(),
                        getRequestingScheme(), getRequestingURL(), getRequestorType());
            }
            return null;
        }
    }
}
