package handler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import Segment.Segment;
import server.Connection;
import server.Flags;
import server.State;
import utils.RequestToBlocks;

public class ServerWriterHandler implements Handler<SelectionKey> {

	// Channel to a Queue ByteBuffer.
	private final Map<DatagramChannel, Queue<ByteBuffer>> pendingData;

	// Connection ID to a Connection.
	final static Map<String, Connection> connections = new HashMap<>();

	// Connection ID to a list of byte buffers of a file
	final static Map<String, RequestToBlocks> connectionToResponse = new HashMap<>();

	String connectionId ="";
	String filename = "";
	
	public ServerWriterHandler(Map<DatagramChannel, Queue<ByteBuffer>> pendingData) {
		this.pendingData = pendingData;
	}
	

	/**
	 * If the request received is a CHLO - call onCHLO() If the request received
	 * is a REQ - call onREQ() If the request received is a ACK - call onACK()
	 */
	@Override
	public void handle(SelectionKey key) throws IOException {

		DatagramChannel channel = (DatagramChannel) key.channel();
		Queue<ByteBuffer> queue = pendingData.get(channel);
		SocketAddress address = (SocketAddress) key.attachment();
		ByteBuffer buffer = queue.poll();
		Segment segment = null;
		try {
			segment = Segment.deserializeBytes(buffer);

		} catch (ClassNotFoundException e) {

			e.printStackTrace();
		}
		filename = segment.filename;
		switch (segment.flag) {

		case CHLO:
			onCHLO(channel, segment, address);
			break;
		case REQ:
			onREQ(channel, segment, address);
			break;
		case ACK:
			onACK(channel, segment, address);
			break;
		case PING:
			onPING(channel, segment, address);
			break;
		default:
			break;

		}
		key.interestOps(SelectionKey.OP_READ);
	}

	/**
	 * On Ping retreive resend the packets from the last seen sequence number sent by client.
	 * As the client might have lost few packets.
	 * 
	 * @param channel
	 * @param segment
	 * @param address
	 * @throws IOException 
	 */
	private void onPING(DatagramChannel channel, Segment segment, SocketAddress address) throws IOException {
		RequestToBlocks request = connectionToResponse.get(segment.connectionId);
		List<ByteBuffer> buf = request.requestToByteBuffer.get(segment.filename);
		Connection con = connections.get(segment.connectionId);
		int SND_UNA = con.SND_UNA;
		int start = segment.sequenceNbr;
		for(int i=start; i<request.requestToByteBuffer.get(segment.filename).size() && i <=SND_UNA;i++){
			System.out.println(address);
			System.out.println(start +" "+ i+ " "+ SND_UNA);
			channel.send(buf.get(i), address);
		}
		
	}


	/**
	 * First time a request a received with CHLO flag. The following function is
	 * called. The server responds to it by sending a SHLO flag with data as
	 * null and ACK being the largest observed sequence number. That is the CHLO
	 * sequence number 0.
	 * 
	 * @param channel
	 * @param segment
	 * @param address
	 * @throws IOException
	 */
	private void onCHLO(DatagramChannel channel, Segment segment, SocketAddress address) throws IOException {
		Connection con = new Connection(address, 1000);
		connections.put(segment.connectionId, con);
		// create a new segment with SHLO flag, data null and ackNbr = +1
		// segment acknowledge number
		Segment seg = new Segment();
		seg.flag = Flags.SHLO;
		seg.NACKs = new ArrayList<>();
		seg.acknowledgementNbr = segment.sequenceNbr;
		seg.connectionId = segment.connectionId;
		sendData(seg, address, channel);
	}

	/***
	 * On receiving a request. Get the connection and update the ip address to
	 * provide client roaming.
	 * 
	 * Create a new instance of get request handler which takes the path from
	 * GET request And reads the file and puts the data in the a map.
	 * 
	 * TODO: Also calls get response handler and send the parts of the data to
	 * client. based on the congestion control algorithms
	 * 
	 * Finally send the packet.
	 * 
	 * @param channel
	 * @param segment
	 * @param address
	 * @throws IOException
	 */
	private void onREQ(DatagramChannel channel, Segment segment, SocketAddress address) throws IOException {
		Connection conn = connections.get(segment.connectionId);
		conn.address = address;
		System.out.println(address);
		String path = new GetRequestHandler(connectionToResponse).handle(segment);
		System.out.println(path);
		this.connectionId = segment.connectionId;
		List<Segment> buf = new ArrayList<>();
		new GetResponseHandler(buf, segment.connectionId, conn, path).handle(connectionToResponse);
		sendBlocks(buf, segment, address,channel);
	}

	/**
	 * On receiving an ACK check for duplicate packets.
	 * TODO: Configure filename.txt
	 * 
	 * @param channel
	 * @param segment
	 * @param address
	 * @throws IOException 
	 */
	private void onACK(DatagramChannel channel, Segment segment, SocketAddress address) throws IOException {
		Connection conn = connections.get(segment.connectionId);
		
		conn.address = address;
		RequestToBlocks req = connectionToResponse.get(segment.connectionId);
		
		if(req.unAckedPack.containsKey(segment.acknowledgementNbr)){
			req.unAckedPack.remove(segment.acknowledgementNbr);
			determineState(conn, segment, req);
		}
		
		if(segment.NACKs.size() == 0 && 
				segment.acknowledgementNbr == req.requestToByteBuffer.get(filename).size()-1){
			sendFin(channel, segment, address);
			return;
		}
		
		List<Segment> buf = new ArrayList<>();
		
		switch (conn.state) {
		case CON_AVD:
			
			break;
		case RETRANSMIT:
			
			break;
		case SLOW:
			conn.updateSenderWindow();
			for(int missing : segment.NACKs){
				System.out.println("Missing ACKS:"+ missing);
				Segment seg = new Segment();
				seg.sequenceNbr = missing;
				seg.flag = Flags.DATA;
				seg.NACKs = new ArrayList<>();
				seg.data = req.getUnAckedPacket(filename, missing).array();
				buf.add(seg);
			}
			new SlowStartHandler(filename,conn, buf).handle(req);
			break;
		default:
			break;

		}
		sendBlocks(buf, segment, address,channel);

	}
	
	private void sendFin(DatagramChannel channel, Segment segment, SocketAddress address) 
			throws IOException {
		Segment seg = new Segment();
		seg.connectionId = connectionId;
		seg.filename = filename;
		seg.flag = Flags.FIN;
		seg.data = null;
		byte[] bytes = Segment.serializeToBytes(seg);
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		channel.send(buffer, address);
	}


	/**
	 * Send blocks to client. Also put the sequence numbers in the un-acked list.
	 * Will be removed as soon as an ack is received.
	 * @param buf			List of buffer 
	 * @param segment		Received Segment from the client
	 * @param address		Socket Address of the client
	 * @param channel		Channel for the connection
	 * @throws IOException
	 */
	public void sendBlocks(List<Segment> buf, Segment segment, SocketAddress address, 
			DatagramChannel channel) throws IOException{
		RequestToBlocks req = connectionToResponse.get(segment.connectionId);
		
		for( Segment seg : buf){
			System.out.println("Sending Segments: "+ seg.sequenceNbr);
			seg.connectionId = this.connectionId;
			req.unAckedPack.put(seg.sequenceNbr, 0);
			sendData(seg, address, channel);
		}
	}

	/***
	 * If atleast 3 dup Acks have been received start fast recovery.
	 * Else if CWND < SSTHRESH  = slow start.
	 * Else if CWND >= SSTHRESH = congestion avoidance.
	 * 
	 * @param conn			connection of the client
	 * @param segment		Segment which is currently being processed.
	 */
	private void determineState(Connection conn, Segment segment, RequestToBlocks req) {
		// 3 dup acks.
		if(req.isDupACK(segment)){
			conn.state = State.RETRANSMIT;
			return;
		}
		// slow start TODO:
		if(conn.MSS < conn.SSTHRESH) {
			conn.state = State.SLOW;
		}else{
			conn.state = State.CON_AVD;
		}
		
	}


	/**
	 * Send the data to the client.
	 * 
	 * @param seg
	 *            New segment
	 * @param address
	 *            Socket Address of the client
	 * @param channel
	 *            Channel of the communication to the client
	 * @throws IOException
	 *             Throws IOException while trying to write to network.
	 */
	public void sendData(Segment seg, SocketAddress address, DatagramChannel channel) throws IOException {
		byte[] packBytes = Segment.serializeToBytes(seg);
		ByteBuffer buffer = ByteBuffer.wrap(packBytes);
		channel.send(buffer, address);
	}
	
	
	/***
	 * For testing purpose only.
	 * 
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		Map<DatagramChannel, Queue<ByteBuffer>> pendingData = new HashMap<>();
		ServerWriterHandler swh = new ServerWriterHandler(pendingData);
		DatagramChannel ch = DatagramChannel.open();
		Segment seg = new Segment();
		SocketAddress add = new InetSocketAddress(8000);
		seg.flag = Flags.CHLO;
		seg.sequenceNbr = 0;
		seg.connectionId = "1234";
		seg.NACKs = new ArrayList<>();
		swh.onCHLO(ch, seg, add);
		seg.flag = Flags.REQ;
		seg.acknowledgementNbr = 0;
		seg.data = "GET hello.txt HTTP/1.1\n".getBytes();
		swh.onREQ(ch, seg, add);
		
		seg.flag = Flags.ACK;
		seg.acknowledgementNbr = 0;
		swh.onACK(ch, seg, add);
		
		seg.flag = Flags.ACK;
		seg.acknowledgementNbr = 1;
		swh.onACK(ch, seg, add);
		
		//seg.NACKs.add(2);
		seg.flag = Flags.ACK;
		seg.acknowledgementNbr = 2;
		swh.onACK(ch, seg, add);
		//
		//Connection conn = new Connection();
		
		//swh.determineState(conn, seg, req);
	}

}
