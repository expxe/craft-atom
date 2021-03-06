package io.craft.atom.nio;

import io.craft.atom.io.ChannelEventType;
import io.craft.atom.io.IoHandler;
import io.craft.atom.io.IoProcessor;
import io.craft.atom.io.IoProcessorX;
import io.craft.atom.io.IoProtocol;
import io.craft.atom.nio.spi.NioChannelEventDispatcher;
import io.craft.atom.util.thread.NamedThreadFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import lombok.ToString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processor process actual I/O operations. 
 * It abstracts Java NIO to simplify transport implementations. 
 * 
 * @author mindwind
 * @version 1.0, Feb 22, 2013
 */
@ToString(callSuper = true, of = { "config", "newChannels", "flushingChannels", "closingChannels", "udpChannels" })
public class NioProcessor extends NioReactor implements IoProcessor {
	
	
	private static final Logger LOG              = LoggerFactory.getLogger(NioProcessor.class);
	private static final long   FLUSH_SPIN_COUNT = 256                                        ;
	private static final long   SELECT_TIMEOUT   = 1000L                                      ;
	
	
	private final    Queue<NioByteChannel>          newChannels       = new ConcurrentLinkedQueue<NioByteChannel>()    ;
    private final    Queue<NioByteChannel>          flushingChannels  = new ConcurrentLinkedQueue<NioByteChannel>()    ;
    private final    Queue<NioByteChannel>          closingChannels   = new ConcurrentLinkedQueue<NioByteChannel>()    ;
    private final    Map<String, NioByteChannel>    udpChannels       = new ConcurrentHashMap<String, NioByteChannel>();
    private final    AtomicReference<ProcessThread> processThreadRef  = new AtomicReference<ProcessThread>()           ;
    private final    NioByteBufferAllocator         allocator         = new NioByteBufferAllocator()                   ;
    private final    AtomicBoolean                  wakeupCalled      = new AtomicBoolean(false)                       ;
    private final    NioChannelIdleTimer            idleTimer                                                          ;
    private final    NioConfig                      config                                                             ;
    private final    Executor                       executor                                                           ;
    private          IoProtocol                     protocol                                                           ;
    private volatile Selector                       selector                                                           ;
    private volatile boolean                        shutdown          = false                                          ;                                         
    
    
	// ~ ------------------------------------------------------------------------------------------------------------
    
    
    NioProcessor(NioConfig config, IoHandler handler, NioChannelEventDispatcher dispatcher, NioChannelIdleTimer idleTimer) {
		this.config     = config;
		this.handler    = handler;
		this.dispatcher = dispatcher;
		this.idleTimer  = idleTimer;
		this.executor   = Executors.newCachedThreadPool(new NamedThreadFactory("craft-atom-nio-processor"));
		
		try {
			selector = Selector.open();
        } catch (IOException e) {
            throw new RuntimeException("Fail to startup a processor", e);
        }
	}
    
    
    // ~ ------------------------------------------------------------------------------------------------------------
    
    
	/**
	 * Adds a nio channel to processor's new channel queue, so that processor can process I/O operations associated this channel.
	 * 
	 * @param channel
	 */
	public void add(NioByteChannel channel) {
		if (this.shutdown) {
			throw new IllegalStateException("The processor already shutdown!");
		}
		
		if (channel == null) {
			LOG.debug("[CRAFT-ATOM-NIO] Add channel is null, return");
			return;
		}
		
		newChannels.add(channel);
		startup();
        wakeup();
	}
	
	private void startup() {
		ProcessThread pt = processThreadRef.get();

        if (pt == null) {
            pt = new ProcessThread();

            if (processThreadRef.compareAndSet(null, pt)) {
                executor.execute(pt);
            }
        }
    }
	
	private void wakeup() {
		wakeupCalled.getAndSet(true);
		selector.wakeup();
	}
	
	/** 
	 * shutdown the processor, stop the process thread and close all the channel within this processor
	 */
	public void shutdown() {
		this.shutdown = true;
		wakeup();
	}
	
	private void shutdown0() throws IOException {
		// close all the channel within this processor
		closingChannels.addAll(newChannels);
		newChannels.clear();
		closingChannels.addAll(flushingChannels);
		flushingChannels.clear();
		close();
		
		// close processor selector
		this.selector.close();
		LOG.debug("[CRAFT-ATOM-NIO] Shutdown processor successful");
	}
	
	private void close() throws IOException {
		for (NioByteChannel channel = closingChannels.poll(); channel != null; channel = closingChannels.poll()) {
			idleTimer.remove(channel);
			if (channel.isClosed()) {
				LOG.debug("[CRAFT-ATOM-NIO] Skip close because it is already closed, |channel={}|", channel);
				continue;
			}
			
			channel.setClosing();
			LOG.debug("[CRAFT-ATOM-NIO] Closing |channel={}|", channel);
			
			close(channel);
			channel.setClosed();
			
			// fire channel closed event
			fireChannelClosed(channel);
			LOG.debug("[CRAFT-ATOM-NIO] Closed |channel={}|" + channel);
		}
	}
	
	private void close(NioByteChannel channel) throws IOException {
		try {
			channel.close0();
			
			if (protocol == IoProtocol.UDP) {
				String key = udpChannelKey(channel.getLocalAddress(), channel.getRemoteAddress());
				udpChannels.remove(key);
			}
		} catch (Exception e) {
			LOG.warn("[CRAFT-ATOM-NIO] Catch close exception and fire it, |channel={}|", channel, e);
			fireChannelThrown(channel, e);
		}
	}
	
	private int select() throws IOException {
		long t0 = System.currentTimeMillis();
		int selected = selector.select(SELECT_TIMEOUT);
		long t1 = System.currentTimeMillis();
		long delta = (t1 - t0);
		
		if ((selected == 0) && !wakeupCalled.get() && (delta < 100)) {
            // the select() may have been interrupted because we have had an closed channel.
            if (isBrokenConnection()) {
                LOG.debug("[CRAFT-ATOM-NIO] Broken connection wakeup");
            } else {
                LOG.debug("[CRAFT-ATOM-NIO] Create a new selector, |selected={}, delta={}|", selected, delta);
                
                // it is a workaround method for jdk bug, see http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6403933
                registerNewSelector();
            }

            // Set back the flag to false and continue the loop
            wakeupCalled.getAndSet(false);
        }
		
		return selected;
	}
	
	private void registerNewSelector() throws IOException {
        synchronized (this) {
            Set<SelectionKey> keys = selector.keys();

            // Open a new selector
            Selector newSelector = Selector.open();

            // Loop on all the registered keys, and register them on the new selector
            for (SelectionKey key : keys) {
                SelectableChannel ch = key.channel();

                // Don't forget to attache the channel, and back !
                NioByteChannel channel = (NioByteChannel) key.attachment();
                ch.register(newSelector, key.interestOps(), channel);
            }

            // Now we can close the old selector and switch it
            selector.close();
            selector = newSelector;
        }
    }
	
	private boolean isBrokenConnection() throws IOException {
		boolean broken = false;
		
		synchronized (selector) {
			Set<SelectionKey> keys = selector.keys();
			for (SelectionKey key : keys) {
				SelectableChannel channel = key.channel();
				if (!((SocketChannel) channel).isConnected()) {
					// The channel is not connected anymore. Cancel the associated key.
					key.cancel();
					broken = true;
				}
			}
		}
		
		return broken;
	}
	
	private void register() throws ClosedChannelException {
		for (NioByteChannel channel = newChannels.poll(); channel != null; channel = newChannels.poll()) {
			SelectableChannel sc = channel.innerChannel();
			SelectionKey key = sc.register(selector, SelectionKey.OP_READ, channel);
			channel.setSelectionKey(key);
			idleTimer.add(channel);
			
			// fire channel opened event
			fireChannelOpened(channel);
		}
	}
	
	private void process() {
		Iterator<SelectionKey> it = selector.selectedKeys().iterator();
		while (it.hasNext()) {
			NioByteChannel channel = (NioByteChannel) it.next().attachment();
			if (channel.isValid()) {
				process0(channel);
			} else {
				LOG.debug("[CRAFT-ATOM-NIO] Channel is invalid, |channel={}|", channel);
			}
			it.remove();
		}
	}
	
	private void process0(NioByteChannel channel) {
		// set last IO time
		channel.setLastIoTime(System.currentTimeMillis());
		
		// Process reads
		if (channel.isReadable()) {
			LOG.debug("[CRAFT-ATOM-NIO] Read event process on |channel={}|", channel);
			read(channel);
		}

		// Process writes
		if (channel.isWritable()) {
			LOG.debug("[CRAFT-ATOM-NIO] Write event process on |channel={}|", channel);
			scheduleFlush(channel);
		}
	}
	
	private void read(NioByteChannel channel) {
		int bufferSize = channel.getPredictor().next();
		ByteBuffer buf = allocator.allocate(bufferSize);
		LOG.debug("[CRAFT-ATOM-NIO] Predict buffer |size={}, buffer={}|", bufferSize, buf);
		
		int readBytes = 0;
		try {
			if (protocol.equals(IoProtocol.TCP)) {
				readBytes = readTcp(channel, buf);
			} else if (protocol.equals(IoProtocol.UDP)) {
				readBytes = readUdp(channel, buf);
			}
		} catch (Exception e) {
			LOG.debug("[CRAFT-ATOM-NIO] Catch read exception and fire it, |channel={}|", channel, e);

			// fire exception caught event
			fireChannelThrown(channel, e);
			
			// if it is IO exception close channel avoid infinite loop.
			if (e instanceof IOException) {
				scheduleClose(channel);
			}
		} finally {
			if (readBytes > 0) { buf.clear(); }
		}
	}
	
	private int readTcp(NioByteChannel channel, ByteBuffer buf) throws IOException {
		int readBytes = 0;
		int ret;
		while ((ret = channel.readTcp(buf)) > 0) {
			readBytes += ret;
			if (!buf.hasRemaining()) {
				break;
			}
		}

		if (readBytes > 0) {
			channel.getPredictor().previous(readBytes);
			fireChannelRead(channel, buf, readBytes);
			LOG.debug("[CRAFT-ATOM-NIO] Actual |readBytes={}|", readBytes);
		}

		// read end-of-stream, remote peer may close channel so close channel.
		if (ret < 0) {
			scheduleClose(channel);
		}
		
		return readBytes;
	}
	
	private void scheduleClose(NioByteChannel channel) {
		if (channel.isClosing() || channel.isClosed()) {
			return;
		}
		
		closingChannels.add(channel);
	}
	
	private int readUdp(NioByteChannel channel, ByteBuffer buf) throws IOException {
		SocketAddress remoteAddress = channel.readUdp(buf);
		if (remoteAddress == null) {
			// no datagram was immediately available
			return 0;
		}
		
		int readBytes = buf.position();
		String key = udpChannelKey(channel.getLocalAddress(), remoteAddress);
		if (!udpChannels.containsKey(key)) {
			// handle first datagram with current channel
			channel.setRemoteAddress(remoteAddress);
			udpChannels.put(key, channel);
		}
		channel.setLastIoTime(System.currentTimeMillis());
		fireChannelRead(channel, buf, buf.position());
		
		return readBytes;
	}
	
	private String udpChannelKey(SocketAddress localAddress, SocketAddress remoteAddress) {
		return localAddress.toString() + "-" + remoteAddress.toString();
	}
	
	/**
	 * Add the channel to the processor's flushing channel queue, and notify processor flush it immediately.
	 * 
	 * @param channel
	 */
	public void flush(NioByteChannel channel) {
		if (this.shutdown) {
			throw new IllegalStateException("The processor is already shutdown!");
		}
		
		if (channel == null) {
			return;
		}
		
		scheduleFlush(channel);
		wakeup();
	}
	
	private void scheduleFlush(NioByteChannel channel) {
		// Add channel to flushing queue if it's not already in the queue, soon after it will be flushed in the same select loop.
		if (channel.setScheduleFlush(true)) {
			flushingChannels.add(channel);
		}
	}
	
	private void flush() {
		int c = 0;
		while (!flushingChannels.isEmpty() && c < FLUSH_SPIN_COUNT) {
			NioByteChannel channel = flushingChannels.poll();
            if (channel == null) {
                // Just in case ... It should not happen.
                break;
            }
            
            // Reset the schedule for flush flag to this channel, as we are flushing it now
            channel.unsetScheduleFlush();
            
            try {
            	if (channel.isClosed() || channel.isClosing()) {
            		LOG.debug("[CRAFT-ATOM-NIO] Channel is closing or closed, |Channel={}, flushing-channel-size={}|", channel, flushingChannels.size());
            		continue;
            	} else {
            		// spin counter avoid infinite loop in this method.
                    c++;
            		flush0(channel);
            	}
			} catch (Exception e) {
				LOG.debug("[CRAFT-ATOM-NIO] Catch flush exception and fire it", e);
				
				// fire channel thrown event 
				fireChannelThrown(channel, e);
				
				// if it is IO exception close channel avoid infinite loop.
				if (e instanceof IOException) {
					scheduleClose(channel);
				}
			}
		}
	}
	
	private void flush0(NioByteChannel channel) throws IOException {
		LOG.debug("[CRAFT-ATOM-NIO] Flushing |channel={}|", channel);
		
		Queue<ByteBuffer> writeQueue = channel.getWriteBufferQueue();

		// First set not be interested to write event
		setInterestedInWrite(channel, false);
		
		// flush by mode
		if (config.isReadWritefair()) {
			fairFlush0(channel, writeQueue);
		} else {
			oneOffFlush0(channel, writeQueue);
		}
		
		// The write buffer queue is not empty, we re-interest in writing and later flush it.
		if (!writeQueue.isEmpty()) {
			setInterestedInWrite(channel, true);
			scheduleFlush(channel);
		}
	}
	
	private void oneOffFlush0(NioByteChannel channel, Queue<ByteBuffer> writeQueue) throws IOException {
		ByteBuffer buf = writeQueue.peek();
		if (buf == null) {
			return;
		}
		
		// fire channel flush event
		fireChannelFlush(channel, buf);
		write(channel, buf, buf.remaining());
		
		if (buf.hasRemaining()) {
			setInterestedInWrite(channel, true);
			scheduleFlush(channel);
			return;
		} else {
			writeQueue.remove();
			
			// fire channel written event
			fireChannelWritten(channel, buf);
		}
	}
	
	private void fairFlush0(NioByteChannel channel, Queue<ByteBuffer> writeQueue) throws IOException {
		ByteBuffer buf = null;
		int writtenBytes = 0;
		final int maxWriteBytes = channel.getMaxWriteBufferSize();
		LOG.debug("[CRAFT-ATOM-NIO] Max write byte size, |maxWriteBytes={}|", maxWriteBytes);
		
		do {
			if (buf == null) {
				buf = writeQueue.peek();
				if (buf == null) {
					return;
				} else {
					// fire channel flush event
					fireChannelFlush(channel, buf);
				}
			}
			
			int qota = maxWriteBytes - writtenBytes;
			int localWrittenBytes = write(channel, buf, qota);
			LOG.debug("[CRAFT-ATOM-NIO] Flush |buffer={}, channel={}, bytes={}, size={}, qota={}, remaining={}|", new String(buf.array()), channel, localWrittenBytes, buf.array().length, qota, buf.remaining());
		
			writtenBytes += localWrittenBytes;
			
			// The buffer is all flushed, remove it from write queue
			if (!buf.hasRemaining()) {
				LOG.debug("[CRAFT-ATOM-NIO] The buffer is all flushed, remove it from write queue");
				
				writeQueue.remove();
				
				// fire channel written event
				fireChannelWritten(channel, buf);
				
				// set buf=null and the next loop if no byte buffer to write then break the loop.
				buf = null;
				continue;
			}

			// 0 byte be written, maybe kernel buffer is full so we re-interest in writing and later flush it.
			if (localWrittenBytes == 0) {
				LOG.debug("[CRAFT-ATOM-NIO] Zero byte be written, maybe kernel buffer is full so we re-interest in writing and later flush it, |channel={}|", channel);
				
				setInterestedInWrite(channel, true);
				scheduleFlush(channel);
				return;
			}
			
			// The buffer isn't empty(bytes to flush more than max bytes), we re-interest in writing and later flush it.
			if (localWrittenBytes > 0 && buf.hasRemaining()) {
				LOG.debug("[CRAFT-ATOM-NIO] The buffer isn't empty, bytes to flush more than max bytes, we re-interest in writing and later flush it, |channel={}|", channel);
				
				setInterestedInWrite(channel, true);
				scheduleFlush(channel);
				return;
			}

			// Wrote too much, so we re-interest in writing and later flush other bytes.
			if (writtenBytes >= maxWriteBytes && buf.hasRemaining()) {
				LOG.debug("[CRAFT-ATOM-NIO] Wrote too much, so we re-interest in writing and later flush other bytes, |channel={}|", channel);
				
				setInterestedInWrite(channel, true);
				scheduleFlush(channel);
				return;
			}
		} while (writtenBytes < maxWriteBytes);
	}
	
	private void setInterestedInWrite(NioByteChannel channel, boolean isInterested) {
		SelectionKey key = channel.getSelectionKey();

		if (key == null || !key.isValid()) {
			return;
		}

		int oldInterestOps = key.interestOps();
		int newInterestOps = oldInterestOps;
		if (isInterested) {
			newInterestOps |= SelectionKey.OP_WRITE;
		} else {
			newInterestOps &= ~SelectionKey.OP_WRITE;
		}

		if (oldInterestOps != newInterestOps) {
            key.interestOps(newInterestOps);
        }
	}
	
	private int write(NioByteChannel channel, ByteBuffer buf, int maxLength) throws IOException {		
		int writtenBytes = 0;
		LOG.debug("[CRAFT-ATOM-NIO] Allow write max len={}, Waiting write byte buffer={}", maxLength, buf); 

		if (buf.hasRemaining()) {
			int length = Math.min(buf.remaining(), maxLength);
			if (protocol.equals(IoProtocol.TCP)) {
				writtenBytes = writeTcp(channel, buf, length);
			} else if (protocol.equals(IoProtocol.UDP)) {
				writtenBytes = writeUdp(channel, buf, length);
			}
		}
		
		LOG.debug("[CRAFT-ATOM-NIO] Actual written byte size, |writtenBytes={}|", writtenBytes);
		return writtenBytes;
	}
	
	private int writeTcp(NioByteChannel channel, ByteBuffer buf, int length) throws IOException {
		if (buf.remaining() <= length) {
			return channel.writeTcp(buf);
		}

		int oldLimit = buf.limit();
		buf.limit(buf.position() + length);
		try {
			return channel.writeTcp(buf);
		} finally {
			buf.limit(oldLimit);
		}
	}
	
	private int writeUdp(NioByteChannel channel, ByteBuffer buf, int length) throws IOException {
		if (buf.remaining() <= length) {
			return channel.writeUdp(buf, channel.getRemoteAddress());
		}

		int oldLimit = buf.limit();
		buf.limit(buf.position() + length);
		try {
			return channel.writeUdp(buf, channel.getRemoteAddress());
		} finally {
			buf.limit(oldLimit);
		}

	}
	
	/**
	 * Removes and closes the specified channel from the processor,
	 * so that processor closes the channel and releases any other related resources.
     * 
	 * @param channel
	 */
    void remove(NioByteChannel channel) {
    	if (this.shutdown) {
			throw new IllegalStateException("The processor is already shutdown!");
		}
		
		if (channel == null) {
			return;
		}
		
		scheduleClose(channel);
		wakeup();
    }
    
	@Override
	public IoProcessorX x() {
		NioProcessorX x = new NioProcessorX();
		x.setNewChannelCount(newChannels.size());
		x.setFlushingChannelCount(flushingChannels.size());
		x.setClosingChannelCount(closingChannels.size());
		return x;
	}
	
	public void setProtocol(IoProtocol protocol) {
		this.protocol = protocol;
	}
	
    
	// ~ -------------------------------------------------------------------------------------------------------------
    
    
    private void fireChannelOpened(NioByteChannel channel) {
    	dispatcher.dispatch(new NioByteChannelEvent(ChannelEventType.CHANNEL_OPENED, channel, handler));
    }
	
	private void fireChannelRead(NioByteChannel channel, ByteBuffer buf, int length) {
		// fire channel received event, here we copy buffer bytes to a new byte array to avoid handler expose <code>ByteBuffer</code> to end user.
		byte[] barr = new byte[length];
		System.arraycopy(buf.array(), 0, barr, 0, length);
		dispatcher.dispatch(new NioByteChannelEvent(ChannelEventType.CHANNEL_READ, channel, handler, barr));
	}
	
	private void fireChannelFlush(NioByteChannel channel, ByteBuffer buf) {
		dispatcher.dispatch(new NioByteChannelEvent(ChannelEventType.CHANNEL_FLUSH, channel, handler, buf.array()));
	}
	
	private void fireChannelWritten(NioByteChannel channel, ByteBuffer buf) {
		dispatcher.dispatch(new NioByteChannelEvent(ChannelEventType.CHANNEL_WRITTEN, channel, handler, buf.array()));
	}
	
	private void fireChannelThrown(NioByteChannel channel, Exception e) {
		dispatcher.dispatch(new NioByteChannelEvent(ChannelEventType.CHANNEL_THROWN, channel, handler, e));
	}
	
	private void fireChannelClosed(NioByteChannel channel) {
		dispatcher.dispatch(new NioByteChannelEvent(ChannelEventType.CHANNEL_CLOSED, channel, handler));
	}
	
	
	// ~ -------------------------------------------------------------------------------------------------------------

	
	private class ProcessThread implements Runnable {
		public void run() {
			while (!shutdown) {
				try {
					int selected = select();
					
					// flush channels
					flush();
					
					// register new channels
					register();
					
					if (selected > 0) { process(); }
					
					// close channels
					close();
				} catch (Exception e) {
					LOG.error("[CRAFT-ATOM-NIO] Process exception", e);
				}
			}
			
			// if shutdown == true, we shutdown the processor
			if (shutdown) {
				try {
					shutdown0();
				} catch (Exception e) {
					LOG.error("[CRAFT-ATOM-NIO] Shutdown exception", e);
				}
			}
		}
	}

}
