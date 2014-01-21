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

import com.turn.ttorrent.client.peer.PeerConnectionListener;
import com.turn.ttorrent.client.io.PeerMessage;
import com.turn.ttorrent.client.peer.DownloadingPiece;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.RateComparator;
import com.turn.ttorrent.client.peer.SharingPeer;

import com.turn.ttorrent.common.Peer;
import io.netty.channel.socket.SocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Incoming peer connections service.
 *
 * <p>
 * Every BitTorrent client, BitTorrent being a peer-to-peer protocol, listens
 * on a port for incoming connections from other peers sharing the same
 * torrent.
 * </p>
 *
 * <p>
 * This ConnectionHandler implements this service and starts a listening socket
 * in the first available port in the default BitTorrent client port range
 * 6881-6889. When a peer connects to it, it expects the BitTorrent handshake
 * message, parses it and replies with our own handshake.
 * </p>
 *
 * <p>
 * Outgoing connections to other peers are also made through this service,
 * which handles the handshake procedure with the remote peer. Regardless of
 * the direction of the connection, once this handshake is successful, all
 * {@link IncomingConnectionListener}s are notified and passed the connected
 * socket and the remote peer ID.
 * </p>
 *
 * <p>
 * This class does nothing more. All further peer-to-peer communication happens
 * in the {@link com.turn.ttorrent.client.peer.PeerExchange PeerExchange}
 * class.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Handshake">BitTorrent handshake specification</a>
 */
public class PeerHandler implements Runnable, PeerConnectionListener, PeerActivityListener {

    private static final Logger logger = LoggerFactory.getLogger(PeerHandler.class);
    /** Peers unchoking frequency, in seconds. Current BitTorrent specification
     * recommends 10 seconds to avoid choking fibrilation. */
    private static final int UNCHOKING_FREQUENCY = 3;
    /** Optimistic unchokes are done every 2 loop iterations, i.e. every
     * 2*UNCHOKING_FREQUENCY seconds. */
    private static final int OPTIMISTIC_UNCHOKE_ITERATIONS = 3;
    private static final int RATE_COMPUTATION_ITERATIONS = 2;
    private static final int MAX_DOWNLOADERS_UNCHOKE = 4;
    /** End-game trigger ratio.
     *
     * <p>
     * Eng-game behavior (requesting already requested pieces from available
     * and ready peers to try to speed-up the end of the transfer) will only be
     * enabled when the ratio of completed pieces over total pieces in the
     * torrent is over this value.
     * </p>
     */
    private static final float END_GAME_COMPLETION_RATIO = 0.95f;
    private final SharedTorrent torrent;
    private final ConcurrentMap<String, SharingPeer> peers = new ConcurrentHashMap<String, SharingPeer>();
    private final AtomicLong uploaded = new AtomicLong(0);
    private final AtomicLong downloaded = new AtomicLong(0);
    @GuardedBy("lock")
    private final BitSet requestedPieces;
    @GuardedBy("lock")
    private final Set<DownloadingPiece> partialPieces = new HashSet<DownloadingPiece>();
    // We only care about global rarest pieces for peer selection or opportunistic unchoking.
    // private final BitSet rarestPieces;
    // private int rarestPiecesAvailability = 0;
    @GuardedBy("lock")
    private int optimisticIterations = 0;
    private final Object lock = new Object();

    /**
     * Create and start a new listening service for out torrent, reporting
     * with our peer ID on the given address.
     *
     * <p>
     * This binds to the first available port in the client port range
     * PORT_RANGE_START to PORT_RANGE_END.
     * </p>
     *
     * @param torrent The torrent shared by this client.
     */
    PeerHandler(@Nonnull SharedTorrent torrent) throws IOException {
        this.torrent = torrent;
        this.requestedPieces = new BitSet(torrent.getPieceCount());
        // this.rarestPieces = new BitSet(torrent.getPieceCount());
    }

    @Nonnull
    public Client getClient() {
        return torrent.getClient();
    }

    @Nonnull
    private Random getRandom() {
        return getClient().getEnvironment().getRandom();
    }

    @Nonnull
    public ConcurrentMap<? extends String, ? extends SharingPeer> getPeers() {
        return peers;
    }

    @Nonnegative
    private int getPeerCount() {
        return peers.size();
    }

    @Nonnegative
    private int getConnectedPeerCount() {
        int count = 0;
        for (Map.Entry<String, SharingPeer> e : peers.entrySet()) {
            SharingPeer peer = e.getValue();
            // Avoid double-counting peers.
            if (e.getKey().equals(peer.getHexPeerId()))
                if (peer.isConnected())
                    count++;
        }
        return count;
    }

    /**
     * Get the number of bytes uploaded for this torrent.
     */
    @Nonnegative
    public long getUploaded() {
        return uploaded.get();
    }

    /**
     * Get the number of bytes downloaded for this torrent.
     *
     * <p>
     * <b>Note:</b> this could be more than the torrent's length, and should
     * not be used to determine a completion percentage.
     * </p>
     */
    @Nonnegative
    public long getDownloaded() {
        return downloaded.get();
    }

    /**
     * Retrieve a SharingPeer object from the given peer specification.
     *
     * <p>
     * This function tries to retrieve an existing peer object based on the
     * provided peer specification or otherwise instantiates a new one and adds
     * it to our peer repository.
     * </p>
     *
     * This method takes two @Nonnull arguments, because Peer has a
     * @CheckForNull on {@link Peer#getPeerId()}.
     */
    @Nonnull
    public SharingPeer getOrCreatePeer(@Nonnull InetSocketAddress remoteAddress, @Nonnull byte[] remotePeerId) {
        Peer remotePeer = new Peer(remoteAddress, remotePeerId);
        logger.trace("Searching for {}...", remotePeer);

        synchronized (this.peers) {
            SharingPeer peer = this.peers.get(remotePeer.getHexPeerId());
            if (peer != null) {
                logger.trace("Found peer (by peer ID): {}.", peer);
                this.peers.put(peer.getHostIdentifier(), peer);
                this.peers.put(remotePeer.getHostIdentifier(), peer);
                return peer;
            }

            peer = this.peers.get(remotePeer.getHostIdentifier());
            if (peer != null) {
                logger.trace("Recording peer ID {} for {}.", remotePeer.getHexPeerId(), peer);
                this.peers.put(remotePeer.getHexPeerId(), peer);
                logger.debug("Found peer (by host ID): {}.", peer);
                return peer;
            }

            peer = new SharingPeer(torrent, remotePeer, this);
            logger.trace("Created new peer: {}.", peer);

            this.peers.put(peer.getHostIdentifier(), peer);
            this.peers.put(peer.getHexPeerId(), peer);

            return peer;
        }
    }

    /**
     * Return a copy of the requested pieces bitset.
     */
    @Nonnull
    public BitSet getRequestedPieces() {
        synchronized (lock) {
            return (BitSet) this.requestedPieces.clone();
        }
    }

    @Nonnegative
    public int getRequestedPieceCount() {
        synchronized (lock) {
            return requestedPieces.cardinality();
        }
    }

    public boolean isRequestedPiece(int index) {
        synchronized (lock) {
            return requestedPieces.get(index);
        }
    }

    private void andNotRequestedPieces(@Nonnull BitSet b) {
        synchronized (lock) {
            b.andNot(requestedPieces);
        }
    }

    /**
     * Connect to the given peer and perform the BitTorrent handshake.
     *
     * <p>
     * Submits an asynchronous connection task to the outbound connections
     * executor to connect to the given peer.
     * </p>
     *
     * @param peer The peer to connect to.
     */
    public void connect(SharingPeer peer) {
        // TODO
        getClient().getPeerClient().connect(peer.getPeer().getAddress(), peer);
    }

    // TODO: Periodic
    @Override
    public void run() {
        optimisticIterations =
                (optimisticIterations == 0
                ? OPTIMISTIC_UNCHOKE_ITERATIONS
                : optimisticIterations - 1);
        unchokePeers(optimisticIterations == 0);
    }

    /**
     * Retrieve a peer comparator.
     *
     * <p>
     * Returns a peer comparator based on either the download rate or the
     * upload rate of each peer depending on our state. While sharing, we rely
     * on the download rate we get from each peer. When our download is
     * complete and we're only seeding, we use the upload rate instead.
     * </p>
     *
     * @return A SharingPeer comparator that can be used to sort peers based on
     * the download or upload rate we get from them.
     */
    private Comparator<SharingPeer> getPeerRateComparator() {
        switch (torrent.getState()) {
            case SHARING:
                return new RateComparator.DLRateComparator();
            case SEEDING:
                return new RateComparator.ULRateComparator();
            default:
                throw new IllegalStateException("Client is neither sharing nor "
                        + "seeding, we shouldn't be comparing peers at this point.");
        }
    }

    /**
     * Unchoke connected peers.
     *
     * <p>
     * This is one of the "clever" places of the BitTorrent client. Every
     * OPTIMISTIC_UNCHOKING_FREQUENCY seconds, we decide which peers should be
     * unchoked and authorized to grab pieces from us.
     * </p>
     *
     * <p>
     * Reciprocation (tit-for-tat) and upload capping is implemented here by
     * carefully choosing which peers we unchoke, and which peers we choke.
     * </p>
     *
     * <p>
     * The four peers with the best download rate and are interested in us get
     * unchoked. This maximizes our download rate as we'll be able to get data
     * from there four "best" peers quickly, while allowing these peers to
     * download from us and thus reciprocate their generosity.
     * </p>
     *
     * <p>
     * Peers that have a better download rate than these four downloaders but
     * are not interested get unchoked too, we want to be able to download from
     * them to get more data more quickly. If one becomes interested, it takes
     * a downloader's place as one of the four top downloaders (i.e. we choke
     * the downloader with the worst upload rate).
     * </p>
     *
     * @param optimistic Whether to perform an optimistic unchoke as well.
     */
    private void unchokePeers(boolean optimistic) {
        // Build a set of all connected peers, we don't care about peers we're
        // not connected to.
        List<SharingPeer> bound = new ArrayList<SharingPeer>();
        for (SharingPeer peer : peers.values())
            if (peer.isConnected() && peer.isChoked() && peer.isInterested())
                bound.add(peer);

        if (bound.isEmpty()) {
            logger.trace("No connected peers, skipping unchoking.");
            return;
        }

        logger.trace("Running unchokePeers() on {} connected peers.", bound.size());
        Collections.sort(bound, getPeerRateComparator());

        int downloaders = 0;
        List<SharingPeer> choked = new ArrayList<SharingPeer>();

        // We're interested in the top downloaders first, so use a descending
        // set.
        for (SharingPeer peer : bound) {
            if (downloaders < MAX_DOWNLOADERS_UNCHOKE) {
                // Unchoke up to MAX_DOWNLOADERS_UNCHOKE interested peers
                if (peer.isChoking()) {
                    if (peer.isInterested()) {
                        downloaders++;
                    }

                    peer.unchoke();
                }
            } else {
                // Choke everybody else
                choked.add(peer);
            }
        }

        // Actually choke all chosen peers (if any), except the eventual
        // optimistic unchoke.
        if (!choked.isEmpty()) {
            int index = getRandom().nextInt(choked.size());
            SharingPeer randomPeer = choked.get(index);

            for (SharingPeer peer : choked) {
                if (optimistic && peer == randomPeer) {
                    logger.debug("Optimistic unchoke of {}.", peer);
                    continue;
                }

                peer.choke();
            }
        }
    }

    /**
     * Computes the set of rarest pieces from the interesting set.
     */
    private static int computeRarestPieces(@Nonnull SharedTorrent torrent, @Nonnull BitSet rarest, @Nonnull BitSet interesting) {
        rarest.clear();
        int rarestAvailability = Integer.MAX_VALUE;
        for (int i = interesting.nextSetBit(0); i >= 0;
                i = interesting.nextSetBit(i + 1)) {
            Piece piece = torrent.getPiece(i);
            int availability = piece.getAvailability();
            if (availability == 0)
                continue;
            if (availability > rarestAvailability)
                continue;
            if (availability < rarestAvailability) {
                rarestAvailability = availability;
                rarest.clear();
            }
            rarest.set(i);
        }
        return rarestAvailability;
    }

    public void addPartiallyDownloadedPiece(@Nonnull DownloadingPiece piece) {
        synchronized (lock) {
            partialPieces.add(piece);
        }
    }

    @CheckForNull
    public DownloadingPiece getNextPieceToDownload(@Nonnull SharingPeer peer) {
        BitSet interesting = peer.getAvailablePieces();

        // TODO: We hold this lock for a LONG time. :-(
        // I'm fairly sure our lock acquisition order is peer then torrent.
        // We can't drop the lock earlier, else two peers will get the
        // same DownloadingPiece, and we don't reference those.
        synchronized (lock) {
            Iterator<DownloadingPiece> it = partialPieces.iterator();
            while (it.hasNext()) {
                DownloadingPiece piece = it.next();
                if (interesting.get(piece.getIndex())) {
                    logger.trace("Peer {} receiving partial piece {}",
                            peer, piece);
                    it.remove();
                    return piece;
                }
            }

            torrent.andNotCompletedPieces(interesting);
            this.andNotRequestedPieces(interesting);
            logger.trace("Peer {} has {} interesting piece(s).",
                    peer, interesting.cardinality());

            // If we didn't find interesting pieces, we need to check if we're in
            // an end-game situation. If yes, we request an already requested piece
            // to try to speed up the end.
            if (interesting.isEmpty()) {
                if (torrent.getCompletedPieceCount() < END_GAME_COMPLETION_RATIO * torrent.getPieceCount()) {
                    logger.trace("Not far along enough to warrant end-game mode.");
                    return null;
                }

                interesting = peer.getAvailablePieces();
                torrent.andNotCompletedPieces(interesting);
                logger.trace("Possible end-game, we're about to request a piece "
                        + "that was already requested from another peer.");
            }

            if (interesting.isEmpty()) {
                logger.trace("No interesting piece from {}!", peer);
                return null;
            }

            BitSet rarestPieces = new BitSet(interesting.length());
            computeRarestPieces(torrent, rarestPieces, interesting);
            if (rarestPieces.isEmpty()) // TODO: This should never happen if we aren't complete.
                return null;
            // Pick a random piece from the rarest pieces from this peer.
            int rarestIndex = getRandom().nextInt(rarestPieces.cardinality());
            Piece rarestPiece;
            SEARCH:
            {
                for (int i = rarestPieces.nextSetBit(0); i >= 0;
                        i = rarestPieces.nextSetBit(i + 1)) {
                    if (rarestIndex-- == 0) {
                        rarestPiece = torrent.getPiece(i);
                        break SEARCH;
                    }
                }
                logger.trace("No rare piece from {}!", peer);
                return null;
            }

            requestedPieces.set(rarestPiece.getIndex());

            logger.trace("Requesting {} from {}, we now have {} "
                    + "outstanding request(s): {}",
                    new Object[]{
                rarestPiece,
                peer,
                this.requestedPieces.cardinality(),
                this.requestedPieces
            });

            return new DownloadingPiece(rarestPiece);
        }
    }

    /** PeerConnectionListener handler(s). ********************************/
    /**
     * Handle a new peer connection.
     *
     * <p>
     * This handler is called once the connection has been successfully
     * established and the handshake exchange made. This generally simply means
     * binding the peer to the socket, which will put in place the communication
     * thread and logic with this peer.
     * </p>
     *
     * @param channel The connected socket channel to the remote peer. Note
     * that if the peer somehow rejected our handshake reply, this socket might
     * very soon get closed, but this is handled down the road.
     * @param peerId The byte-encoded peerId extracted from the peer's
     * handshake, after validation.
     * @see com.turn.ttorrent.client.peer.SharingPeer
     */
    @Override
    public void handleNewPeerConnection(SocketChannel channel, SharingPeer peer) {
        logger.info("Handling new peer connection with {}...", peer);

        try {
            synchronized (peer) {
                if (peer.isConnected()) {
                    logger.info("Already connected with {}, closing new link.",
                            peer);
                    channel.close();
                    return;
                }

                peer.setChannel(channel);
            }

            logger.debug("New peer connection with {} [{}/{}].",
                    new Object[]{
                peer,
                getConnectedPeerCount(),
                getPeerCount()
            });
        } catch (Exception e) {
            logger.warn("Could not handle new peer connection "
                    + "with {}: {}", peer, e.getMessage());
        }
    }

    /**
     * Handle a failed peer connection.
     *
     * <p>
     * If an outbound connection failed (could not connect, invalid handshake,
     * etc.), remove the peer from our known peers.
     * </p>
     *
     * @param peer The peer we were trying to connect with.
     * @param cause The exception encountered when connecting with the peer.
     */
    @Override
    public void handleFailedConnection(SharingPeer peer, Throwable cause) {
        logger.warn("Could not connect to {}: {}.", peer, cause.getMessage());
        synchronized (peers) {
            peers.remove(peer.getHostIdentifier());
            peers.remove(peer.getHexPeerId());
        }
    }

    /** PeerActivityListener handler(s). *************************************/
    private void handlePeerPipelineDiscard(SharingPeer peer) {
        /*
        synchronized (lock) {
            Piece piece = peer.getRequestedPiece();
            if (piece != null) {
                requestedPieces.set(piece.getIndex(), false);
            }
        }
        */
    }

    /**
     * Peer choked handler.
     *
     * <p>
     * When a peer chokes, the requests made to it are cancelled and we need to
     * mark the eventually piece we requested from it as available again for
     * download tentative from another peer.
     * </p>
     *
     * @param peer The peer that choked.
     */
    @Override
    public void handlePeerChoking(SharingPeer peer) {
        handlePeerPipelineDiscard(peer);

        logger.trace("Peer {} choked, we now have {} outstanding "
                + "request(s): {}",
                new Object[]{
            peer,
            getRequestedPieceCount(),
            getRequestedPieces()
        });
    }

    /**
     * Peer ready handler.
     *
     * <p>
     * When a peer becomes ready to accept piece block requests, select a piece
     * to download and go for it.
     * </p>
     *
     * @param peer The peer that became ready.
     */
    @Override
    public void handlePeerUnchoking(SharingPeer peer) {
        logger.trace("Peer {} is ready and has {} piece(s).",
                peer, peer.getAvailablePieceCount());
    }

    /**
     * Piece availability handler.
     *
     * <p>
     * Handle updates in piece availability from a peer's HAVE message. When
     * this happens, we need to mark that piece as available from the peer.
     * </p>
     *
     * @param peer The peer we got the update from.
     * @param piece The piece that became available.
     */
    @Override
    public void handlePieceAvailability(SharingPeer peer, Piece piece) {
        /* int availability = */ piece.seenAt(peer);

        logger.trace("Peer {} contributes {} piece(s) [{}/{}/{}].",
                new Object[]{
            peer,
            peer.getAvailablePieceCount(),
            torrent.getCompletedPieceCount(),
            torrent.getAvailablePieceCount(),
            torrent.getPieceCount()
        });
    }

    /**
     * Bit field availability handler.
     *
     * <p>
     * Handle updates in piece availability from a peer's BITFIELD message.
     * When this happens, we need to mark in all the pieces the peer has that
     * they can be reached through this peer, thus augmenting the global
     * availability of pieces.
     * </p>
     *
     * @param peer The peer we got the update from.
     * @param availablePieces The pieces availability bit field of the peer.
     */
    @Override
    public void handleBitfieldAvailability(SharingPeer peer,
            BitSet prevAvailablePieces,
            BitSet availablePieces) {

        // Record that the peer no longer has all the pieces it previously told us it had.
        for (int i = prevAvailablePieces.nextSetBit(0); i >= 0;
                i = prevAvailablePieces.nextSetBit(i + 1)) {
            if (!availablePieces.get(i))
                torrent.getPiece(i).noLongerAt(peer);
        }

        // Record that the peer has all the pieces it told us it had.
        for (int i = availablePieces.nextSetBit(0); i >= 0;
                i = availablePieces.nextSetBit(i + 1)) {
            if (!prevAvailablePieces.get(i))
                torrent.getPiece(i).seenAt(peer);
        }

        // Determine if the peer is interesting for us or not, and notify it.
        BitSet interesting = availablePieces;
        torrent.andNotCompletedPieces(interesting);
        this.andNotRequestedPieces(interesting);

        /*
         if (interesting.isEmpty())
         peer.notInteresting();
         else
         peer.interesting();
         */

        logger.trace("Peer {} contributes {} piece(s) ({} interesting) "
                + "[completed={}; available={}/{}].",
                new Object[]{
            peer,
            availablePieces.cardinality(),
            interesting.cardinality(),
            torrent.getCompletedPieceCount(),
            torrent.getAvailablePieceCount(),
            torrent.getPieceCount()
        });
    }

    /**
     * Block upload handler.
     *
     * <p>
     * When a block has been sent to a peer, we just record that we sent that
     * many bytes. If the piece is valid on the peer's side, it will send us a
     * HAVE message and we'll record that the piece is available on the peer at
     * that moment (see <code>handlePieceAvailability()</code>).
     * </p>
     *
     * @param peer The peer we got this piece from.
     * @param piece The piece in question.
     */
    @Override
    public void handleBlockSent(SharingPeer peer, Piece piece, int offset, int length) {
        this.uploaded.addAndGet(length);
    }

    @Override
    public void handleBlockReceived(SharingPeer peer, Piece piece, int offset, int length) {
        this.downloaded.addAndGet(length);
    }

    /**
     * Piece download completion handler.
     *
     * <p>
     * When a piece is completed, and valid, we announce to all connected peers
     * that we now have this piece.
     * </p>
     *
     * <p>
     * We use this handler to identify when all of the pieces have been
     * downloaded. When that's the case, we can start the seeding period, if
     * any.
     * </p>
     *
     * @param peer The peer we got the piece from.
     * @param piece The piece in question.
     */
    @Override
    public void handlePieceCompleted(SharingPeer peer, Piece piece)
            throws IOException {
        // Regardless of validity, record the number of bytes downloaded and
        // mark the piece as not requested anymore
        this.requestedPieces.set(piece.getIndex(), false);

        if (piece.isValid()) {
            // Make sure the piece is marked as completed in the torrent
            // Note: this is required because the order the
            // PeerActivityListeners are called is not defined, and we
            // might be called before the torrent's piece completion
            // handler is.
            this.torrent.setCompletedPiece(piece.getIndex());
            logger.debug("Completed download of {} from {}. "
                    + "We now have {}/{} pieces",
                    new Object[]{
                piece,
                peer,
                this.torrent.getCompletedPieceCount(),
                this.torrent.getPieceCount()
            });

            // Send a HAVE message to all connected peers
            PeerMessage have = new PeerMessage.HaveMessage(piece.getIndex());
            for (SharingPeer remote : peers.values())
                if (remote.isConnected())
                    remote.send(have);
        } else {
            logger.warn("Downloaded piece#{} from {} was not valid ;-(",
                    piece.getIndex(), peer);
        }

        if (this.torrent.isComplete()) {
            logger.info("Last piece validated and completed, finishing download...");

            // Cancel all remaining outstanding requests
            for (SharingPeer remote : peers.values()) {
                int requests = remote.cancelRequestsSent();
                logger.info("Cancelled {} remaining pending requests on {}.",
                        requests, remote);
            }

            // TODO: This need locking.
            this.torrent.finish();
            logger.info("Download is complete and finalized.");
        }

        logger.trace("We now have {} piece(s) and {} outstanding request(s): {}",
                new Object[]{
            torrent.getCompletedPieceCount(),
            getRequestedPieceCount(),
            getRequestedPieces()
        });
    }

    /**
     * Peer disconnection handler.
     *
     * <p>
     * When a peer disconnects, we need to mark in all of the pieces it had
     * available that they can't be reached through this peer anymore.
     * </p>
     *
     * @param peer The peer we got this piece from.
     */
    @Override
    public void handlePeerDisconnected(SharingPeer peer) {
        logger.debug("Peer {} disconnected, [{}/{}].",
                new Object[]{
            peer,
            getConnectedPeerCount(),
            getPeerCount()
        });

        BitSet availablePieces = peer.getAvailablePieces();

        for (int i = availablePieces.nextSetBit(0); i >= 0;
                i = availablePieces.nextSetBit(i + 1)) {
            torrent.getPiece(i).noLongerAt(peer);
        }

        handlePeerPipelineDiscard(peer);

        peer.reset();

        logger.debug("Peer {} went away with {} piece(s) [completed={}; available={}/{}]",
                new Object[]{
            peer,
            peer.getAvailablePieceCount(),
            torrent.getCompletedPieceCount(),
            torrent.getAvailablePieceCount(),
            torrent.getPieceCount()
        });
        logger.trace("We now have {} piece(s) and {} outstanding request(s): {}",
                new Object[]{
            torrent.getCompletedPieceCount(),
            getRequestedPieceCount(),
            getRequestedPieces() // This has to copy because logger might stringify it asynchronouly.
        });
    }

    @Override
    public void handleIOException(SharingPeer peer, IOException ioe) {
        logger.warn("I/O error while exchanging data with " + peer + ", "
                + "closing connection with it!", ioe);
        peer.setChannel(null);
    }
}