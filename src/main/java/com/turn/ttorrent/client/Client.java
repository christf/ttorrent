/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.turn.ttorrent.client.io.PeerClient;
import com.turn.ttorrent.client.io.PeerServer;
import com.turn.ttorrent.client.tracker.HTTPTrackerClient;
import com.turn.ttorrent.common.Torrent;

/**
 * A pure-java BitTorrent client.
 * 
 * <p>
 * A BitTorrent client in its bare essence shares torrents. If the 
 * torrent is not complete locally, it will continue to download it. If or
 * after the torrent is complete, the client may eventually continue to seed it 
 * for other clients.
 * </p>
 *
 * <p>
 * This BitTorrent client implementation is made to be simple to embed and
 * simple to use. First, initialize a ShareTorrent object from a torrent
 * meta-info source (either a file or a byte array, see
 * com.turn.ttorrent.SharedTorrent for how to create a SharedTorrent object).
 * Then, instantiate your Client object with this SharedTorrent and call one
 * of {@link #download} to simply download the torrent, or {@link #share} to
 * download and continue seeding for the given amount of time after the
 * download completes.
 * </p>
 * 
 * @author mpetazzoni
 */
public class Client {

    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    public enum State {

        STOPPED, STARTING, STARTED, STOPPING;
    }

    private final ClientEnvironment environment;
    @GuardedBy("lock")
    private State state = State.STOPPED;
    private PeerServer peerServer;
    private PeerClient peerClient;
    private HTTPTrackerClient httpTrackerClient;
    // private UDPTrackerClient udpTrackerClient;
    // TODO: Search ports for a free port.
    private final ConcurrentMap<String, TorrentHandler> torrents = new ConcurrentHashMap<String, TorrentHandler>();
    private final List<ClientListener> listeners = new CopyOnWriteArrayList<ClientListener>();
    private final Object lock = new Object();
    InetSocketAddress localAddress;

    /**
     * Initialize the BitTorrent client.
     * 
     * @param localAddress The address & port where this client should run
     */
    public Client(InetSocketAddress localAddress) {
        this(null, localAddress);
    }

    /**
     * Initialize the BitTorrent client.
     * 
     * @param peerName the name of the client.
     * @param localAddress The address & port where this client should run
     */
    public Client(@CheckForNull String peerName, InetSocketAddress localAddress) {
        this.environment = new ClientEnvironment(peerName);
        this.localAddress = localAddress;
    }

    /**
     * A convenience constructor to start with a single torrent. The name of
     * the client will be the the name of the torrent.
     * 
     * @param torrent The torrent to download and share.
     * @param outputDir The location where the data should be placed.
     * @param localAddress Which address & Port to use.
     */
    public Client(@Nonnull Torrent torrent, @Nonnull File outputDir,
            InetSocketAddress localAddress) throws IOException, InterruptedException {
        this(torrent.getName(), localAddress);
        addTorrent(new TorrentHandler(this, torrent, outputDir));

    }

    @Nonnull
    public ClientEnvironment getEnvironment() {
        return environment;
    }

    @Nonnull
    public byte[] getLocalPeerId() {
        return getEnvironment().getLocalPeerId();
    }

    @Nonnull
    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    private void setState(@Nonnull State state) {
        synchronized (lock) {
            this.state = state;
        }
        fireClientState(state);
    }

    @Nonnull
    public PeerServer getPeerServer() {
        return peerServer;
    }

    @Nonnull
    public PeerClient getPeerClient() {
        return peerClient;
    }

    @Nonnull
    public HTTPTrackerClient getHttpTrackerClient() {
        synchronized (lock) {
            HTTPTrackerClient h = httpTrackerClient;
            if (h == null)
                throw new IllegalStateException("No HTTPTrackerClient - bad state: " + this);
            return h;
        }
    }

    /*
     @Nonnull public UDPTrackerClient getUdpTrackerClient() { return
     udpTrackerClient; 
     }
     */
    public void start() throws Exception {
        LOG.info("BitTorrent client [{}] starting...", this);
        synchronized (lock) {
            setState(State.STARTING);

            environment.start();

            peerServer = new PeerServer(this, localAddress);
            peerServer.start();
            peerClient = new PeerClient(this);
            peerClient.start();

            httpTrackerClient = new HTTPTrackerClient(environment, localAddress);
            httpTrackerClient.start();

            // udpTrackerClient = new UDPTrackerClient(environment, peer);
            // udpTrackerClient.start();

            for (TorrentHandler torrent : torrents.values())
                torrent.start();

            setState(State.STARTED);
        }
        LOG.info("BitTorrent client [{}] started...", this);
    }

    public void stop() throws Exception {
        LOG.info("BitTorrent client [{}] stopping...", this);
        synchronized (lock) {
            setState(State.STOPPING);

            for (TorrentHandler torrent : torrents.values())
                torrent.stop();

            // if (udpTrackerClient != null)
            // udpTrackerClient.stop();
            // udpTrackerClient = null;
            if (httpTrackerClient != null)
                httpTrackerClient.stop();
            httpTrackerClient = null;
            if (peerClient != null)
                peerClient.stop();
            peerClient = null;
            if (peerServer != null)
                peerServer.stop();
            environment.stop();

            setState(State.STOPPED);
        }
        LOG.info("BitTorrent client [{}] stopped...", this);
    }

    @CheckForNull
    public TorrentHandler getTorrent(@Nonnull byte[] infoHash) {
        String hexInfoHash = Torrent.byteArrayToHexString(infoHash);
        return torrents.get(hexInfoHash);
    }

    public void addTorrent(@Nonnull TorrentHandler torrent) throws IOException, InterruptedException {
        // This lock guarantees that we are started or stopped.
        synchronized (lock) {
            torrents.put(Torrent.byteArrayToHexString(torrent.getInfoHash()), torrent);
            if (getState() == State.STARTED)
                torrent.start();
        }
    }

    public void removeTorrent(@Nonnull TorrentHandler torrent) {
        synchronized (lock) {
            torrents.remove(Torrent.byteArrayToHexString(torrent.getInfoHash()), torrent);
            if (getState() == State.STARTED)
                torrent.stop();
        }
    }

    @CheckForNull
    public TorrentHandler removeTorrent(@Nonnull Torrent torrent) {
        return removeTorrent(torrent.getInfoHash());
    }

    @CheckForNull
    public TorrentHandler removeTorrent(@Nonnull byte[] infoHash) {
        TorrentHandler torrent = torrents.get(Torrent.byteArrayToHexString(infoHash));
        if (torrent != null)
            removeTorrent(torrent);
        return torrent;
    }

    public void addClientListener(@Nonnull ClientListener listener) {
        listeners.add(listener);
    }

    private void fireClientState(@Nonnull State state) {
        for (ClientListener listener : listeners)
            listener.clientStateChanged(this, state);
    }

    public void fireTorrentState(@Nonnull TorrentHandler torrent, @Nonnull TorrentHandler.State state) {
        for (ClientListener listener : listeners)
            listener.torrentStateChanged(this, torrent, state);
    }

    public void info(boolean verbose) {
        for (Map.Entry<String, TorrentHandler> e : torrents.entrySet()) {
            e.getValue().info(verbose);
        }
    }

}