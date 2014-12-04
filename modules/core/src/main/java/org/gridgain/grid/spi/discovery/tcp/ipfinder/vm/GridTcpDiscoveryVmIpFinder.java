/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.spi.discovery.tcp.ipfinder.vm;

import org.apache.ignite.*;
import org.apache.ignite.resources.*;
import org.gridgain.grid.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.*;
import org.gridgain.grid.util.tostring.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.net.*;
import java.util.*;

import static org.gridgain.grid.GridSystemProperties.*;

/**
 * IP Finder which works only with pre-configured list of IP addresses specified
 * via {@link #setAddresses(Collection)} method. By default, this IP finder is
 * not {@code shared}, which means that all grid nodes have to be configured with the
 * same list of IP addresses when this IP finder is used.
 * <h1 class="header">Configuration</h1>
 * <h2 class="header">Mandatory</h2>
 * There are no mandatory configuration parameters.
 * <h2 class="header">Optional</h2>
 * <ul>
 *      <li>Addresses for initialization (see {@link #setAddresses(Collection)})</li>
 *      <li>Shared flag (see {@link #setShared(boolean)})</li>
 * </ul>
 */
public class GridTcpDiscoveryVmIpFinder extends GridTcpDiscoveryIpFinderAdapter {
    /** Grid logger. */
    @IgniteLoggerResource
    private IgniteLogger log;

    /** Addresses. */
    @GridToStringInclude
    private Collection<InetSocketAddress> addrs;

    /**
     * Initialize from system property.
     */
    {
        String ips = GridSystemProperties.getString(GG_TCP_DISCOVERY_ADDRESSES);

        if (!F.isEmpty(ips)) {
            Collection<InetSocketAddress> addrsList = new LinkedHashSet<>();

            for (String s : ips.split(",")) {
                if (!F.isEmpty(s)) {
                    s = s.trim();

                    if (!F.isEmpty(s)) {
                        try {
                            addrsList.addAll(address(s));
                        }
                        catch (GridSpiException e) {
                            throw new GridRuntimeException(e);
                        }
                    }
                }
            }

            addrs = addrsList;
        }
        else
            addrs = new LinkedHashSet<>();
    }

    /**
     * Constructs new IP finder.
     */
    public GridTcpDiscoveryVmIpFinder() {
        // No-op.
    }

    /**
     * Constructs new IP finder.
     *
     * @param shared {@code true} if IP finder is shared.
     * @see #setShared(boolean)
     */
    public GridTcpDiscoveryVmIpFinder(boolean shared) {
        setShared(shared);
    }

    /**
     * Parses provided values and initializes the internal collection of addresses.
     * <p>
     * Addresses may be represented as follows:
     * <ul>
     *     <li>IP address (e.g. 127.0.0.1, 9.9.9.9, etc);</li>
     *     <li>IP address and port (e.g. 127.0.0.1:47500, 9.9.9.9:47501, etc);</li>
     *     <li>IP address and port range (e.g. 127.0.0.1:47500..47510, 9.9.9.9:47501..47504, etc);</li>
     *     <li>Hostname (e.g. host1.com, host2, etc);</li>
     *     <li>Hostname and port (e.g. host1.com:47500, host2:47502, etc).</li>
     *     <li>Hostname and port range (e.g. host1.com:47500..47510, host2:47502..47508, etc).</li>
     * </ul>
     * <p>
     * If port is 0 or not provided then default port will be used (depends on
     * discovery SPI configuration).
     * <p>
     * If port range is provided (e.g. host:port1..port2) the following should be considered:
     * <ul>
     *     <li>{@code port1 < port2} should be {@code true};</li>
     *     <li>Both {@code port1} and {@code port2} should be greater than {@code 0}.</li>
     * </ul>
     *
     * @param addrs Known nodes addresses.
     * @throws GridSpiException If any error occurs.
     */
    @GridSpiConfiguration(optional = true)
    public synchronized void setAddresses(Collection<String> addrs) throws GridSpiException {
        if (F.isEmpty(addrs))
            return;

        Collection<InetSocketAddress> newAddrs = new LinkedHashSet<>();

        for (String ipStr : addrs)
            newAddrs.addAll(address(ipStr));

        this.addrs = newAddrs;
    }

    /**
     * Creates address from string.
     *
     * @param ipStr Address string.
     * @return Socket addresses (may contain 1 or more addresses if provided string
     *      includes port range).
     * @throws GridSpiException If failed.
     */
    private static Collection<InetSocketAddress> address(String ipStr) throws GridSpiException {
        ipStr = ipStr.trim();

        String errMsg = "Failed to parse provided address: " + ipStr;

        int colonCnt = ipStr.length() - ipStr.replace(":", "").length();

        if (colonCnt > 1) {
            // IPv6 address (literal IPv6 addresses are enclosed in square brackets, for example
            // https://[2001:db8:85a3:8d3:1319:8a2e:370:7348]:443).
            if (ipStr.startsWith("[")) {
                ipStr = ipStr.substring(1);

                if (ipStr.contains("]:"))
                    return addresses(ipStr, "\\]\\:", errMsg);
                else if (ipStr.endsWith("]"))
                    ipStr = ipStr.substring(0, ipStr.length() - 1);
                else
                    throw new GridSpiException(errMsg);
            }
        }
        else {
            // IPv4 address.
            if (ipStr.endsWith(":"))
                ipStr = ipStr.substring(0, ipStr.length() - 1);
            else if (ipStr.indexOf(':') >= 0)
                return addresses(ipStr, "\\:", errMsg);
        }

        // Provided address does not contain port (will use default one).
        return Collections.singleton(new InetSocketAddress(ipStr, 0));
    }

    /**
     * Creates address from string with port information.
     *
     * @param ipStr Address string
     * @param regexDelim Port regex delimiter.
     * @param errMsg Error message.
     * @return Socket addresses (may contain 1 or more addresses if provided string
     *      includes port range).
     * @throws GridSpiException If failed.
     */
    private static Collection<InetSocketAddress> addresses(String ipStr, String regexDelim, String errMsg)
        throws GridSpiException {
        String[] tokens = ipStr.split(regexDelim);

        if (tokens.length == 2) {
            String addrStr = tokens[0];
            String portStr = tokens[1];

            if (portStr.contains("..")) {
                try {
                    int port1 = Integer.parseInt(portStr.substring(0, portStr.indexOf("..")));
                    int port2 = Integer.parseInt(portStr.substring(portStr.indexOf("..") + 2, portStr.length()));

                    if (port2 < port1 || port1 == port2 || port1 <= 0 || port2 <= 0)
                        throw new GridSpiException(errMsg);

                    Collection<InetSocketAddress> res = new ArrayList<>(port2 - port1);

                    // Upper bound included.
                    for (int i = port1; i <= port2; i++)
                        res.add(new InetSocketAddress(addrStr, i));

                    return res;
                }
                catch (IllegalArgumentException e) {
                    throw new GridSpiException(errMsg, e);
                }
            }
            else {
                try {
                    int port = Integer.parseInt(portStr);

                    return Collections.singleton(new InetSocketAddress(addrStr, port));
                }
                catch (IllegalArgumentException e) {
                    throw new GridSpiException(errMsg, e);
                }
            }
        }
        else
            throw new GridSpiException(errMsg);
    }

    /** {@inheritDoc} */
    @Override public synchronized Collection<InetSocketAddress> getRegisteredAddresses() {
        return Collections.unmodifiableCollection(addrs);
    }

    /** {@inheritDoc} */
    @Override public synchronized void registerAddresses(Collection<InetSocketAddress> addrs) {
        assert !F.isEmpty(addrs);

        this.addrs = new LinkedHashSet<>(this.addrs);

        this.addrs.addAll(addrs);
    }

    /** {@inheritDoc} */
    @Override public synchronized void unregisterAddresses(Collection<InetSocketAddress> addrs) {
        assert !F.isEmpty(addrs);

        this.addrs = new LinkedHashSet<>(this.addrs);

        this.addrs.removeAll(addrs);
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridTcpDiscoveryVmIpFinder.class, this, "super", super.toString());
    }
}
