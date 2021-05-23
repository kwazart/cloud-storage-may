package com.polozov.nio;

import com.polozov.client.Client;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class NioTelnetServer {
	public static final String LS_COMMAND = "\tls    view all files and directories\n";
	public static final String MKDIR_COMMAND = "\tmkdir    create directory\n";
	public static final String CHANGE_NICKNAME = "\tnick    change nickname\n";
	private static final String ROOT_NOTIFICATION = "You are already in the root directory\n\n";
	private static final String ROOT_PATH = "src";
	private static final String DIRECTORY_DOESNT_EXIST="Directory %s doesn't exist\n\n";

	private Path currentPath=Path.of("src");

	private final ByteBuffer buffer = ByteBuffer.allocate(512);

	private Map<SocketAddress,String> clients=new HashMap<>();
	private SecureDirectoryStream<Object> FileUtils;

	public NioTelnetServer() throws IOException {
		ServerSocketChannel server = ServerSocketChannel.open();
		server.bind(new InetSocketAddress(5678));
		server.configureBlocking(false);
		// OP_ACCEPT, OP_READ, OP_WRITE
		Selector selector = Selector.open();

		server.register(selector, SelectionKey.OP_ACCEPT);
		System.out.println("Server started");

		while (server.isOpen()) {
			selector.select();

			var selectionKeys = selector.selectedKeys();
			var iterator = selectionKeys.iterator();

			while (iterator.hasNext()) {
				var key = iterator.next();
				if (key.isAcceptable()) {
					handleAccept(key, selector);
				} else if (key.isReadable()) {
					handleRead(key, selector);
				}
				iterator.remove();
			}
		}
	}

	private void handleRead(SelectionKey key, Selector selector) throws IOException {
		SocketChannel channel = ((SocketChannel) key.channel());
		SocketAddress client = channel.getRemoteAddress();

		String nickname="";

		int readBytes = channel.read(buffer);
		if (readBytes < 0) {
			channel.close();
			return;
		} else if (readBytes == 0) {
			return;
		}

		buffer.flip();

		StringBuilder sb = new StringBuilder();
		while (buffer.hasRemaining()) {
			sb.append((char) buffer.get());
		}

		buffer.clear();

		// TODO
		// touch [filename] - создание файла
		// mkdir [dirname] - создание директории
		// cd [path] - перемещение по каталогу (.. | ~ )
		// rm [filename | dirname] - удаление файла или папки
		// copy [src] [target] - копирование файла или папки
		// cat [filename] - просмотр содержимого
		// вывод nickname в начале строки

		// NIO
		// NIO telnet server

		if (key.isValid()) {
			String command = sb
					.toString()
					.replace("\n", "")
					.replace("\r", "");

			if ("--help".equals(command)) {
				sendMessage(LS_COMMAND, selector, client);
				sendMessage(MKDIR_COMMAND, selector, client);
				sendMessage(CHANGE_NICKNAME, selector, client);
			} else if ("ls".equals(command)) {
				sendMessage(getFileList(currentPath).concat("\n"), selector, client);
			}else if(command.startsWith("nick")) {
				nickname = changeName(channel,command);
			}else if(command.startsWith("mkdir")){
				makeDir(command);
			}else if(command.startsWith("rm")){
				removeSmth(command);
			}else if(command.startsWith("copy")){
				duplicate(command);
			}else if(command.startsWith("cat")){
				readFile(command,selector,client);
			}else if(command.startsWith("cd")){
				replacePosition(selector,client,command);
			}else if(command.startsWith("touch")){
				mkfile(selector,client,command);
			} else if ("exit".equals(command)) {
				System.out.println("Client logged out. IP: " + channel.getRemoteAddress());
				channel.close();
				return;
			}
		}
		sendName(channel,nickname);
	}

	private void mkfile(Selector selector, SocketAddress client, String command) throws IOException {
		String fileName = command.split(" ")[1];
		Path tempPath=Path.of(currentPath.toString());
		File file = new File(tempPath+"/"+fileName);
		if (!file.exists()) {
			file.createNewFile();
		}
	}

	private void readFile(String command, Selector selector, SocketAddress client) throws IOException {
		String fileName = command.split(" ")[1];
		Path tempPath=Path.of(currentPath.toString());
		File file = new File(tempPath+"/"+fileName);
		if (!file.exists()) {
			throw new FileNotFoundException();
		}

		BufferedReader reader = new BufferedReader (new InputStreamReader (System.in));
		InputStream inStream = new FileInputStream (file);

		StringBuilder str=new StringBuilder("");
		while (inStream.available () > 0) {
			str.append((char) inStream.read ());

		}

		System.out.println(str.toString());
		sendMessage(str.toString()+"\n",selector,client);

		inStream.close (); //закрываем потоки
		reader.close ();
	}


	private void duplicate(String command) throws IOException {
		String fileName = command.split(" ")[1];
		String fileTo = command.split(" ")[2];
		Path tempPath=Path.of(currentPath.toString());
		Path bytes = Files.copy(
				new File(tempPath+"/"+fileName).toPath(),
				new File(fileTo).toPath(),
				REPLACE_EXISTING,
				StandardCopyOption.COPY_ATTRIBUTES,
				LinkOption.NOFOLLOW_LINKS);
	}


	private void removeSmth(String command) throws IOException {
		String fileOrDir=command.split(" ")[1];
		Path tempPath=Path.of(currentPath.toString());
		File f=new File(tempPath+"/"+fileOrDir);
		f.delete();
	}

	private void makeDir(String command) throws IOException {
		String neededDir=command.split(" ")[1];
		Path tempPath=Path.of(currentPath.toString());
		Files.createDirectory(Paths.get(String.valueOf(tempPath+"/"+neededDir)));
	}

	private String changeName(SocketChannel channel, String command) throws IOException {
		String nickname;
		nickname=command.split(" ")[1];
		clients.put(channel.getRemoteAddress(),nickname);
		System.out.println(
				"Clients- " +channel.getRemoteAddress().toString()+" changed nickname on " + nickname
		);
		System.out.println(clients);
		return nickname;
	}

	private void replacePosition(Selector selector, SocketAddress client, String command) throws IOException {
		String neededPathString=command.split(" ")[1];
		Path tempPath=Path.of(currentPath.toString(),neededPathString);
		if("..".equals(neededPathString)){
			tempPath=currentPath.getParent();
			if(tempPath==null || !tempPath.toString().startsWith("src")){
				sendMessage(ROOT_NOTIFICATION,selector,client);
			}else{
				currentPath=tempPath;
			}
		}else if("~".equals(neededPathString)){
			currentPath=Path.of(ROOT_PATH);
		}else{
			if(tempPath.toFile().exists()){
				currentPath=tempPath;
			}else{
				sendMessage(String.format(DIRECTORY_DOESNT_EXIST, neededPathString),selector,client);
			}
		}
	}

	private void sendName(SocketChannel channel, String nickname) throws IOException {
		if(nickname.isEmpty()){
			nickname=clients.getOrDefault(channel.getRemoteAddress(),channel.getRemoteAddress().toString());
		}
		String currentPathString=currentPath.toString().replace("src","~");

		channel.write(
				ByteBuffer.wrap(nickname.concat(">:").concat(currentPathString).concat("$ ")
						.getBytes(StandardCharsets.UTF_8)
				));
	}

	private String getFileList(Path path) {
		return String.join(" ", new File(String.valueOf(path)).list());
	}

	private void sendMessage(String message, Selector selector, SocketAddress client) throws IOException {
		for (SelectionKey key : selector.keys()) {
			if (key.isValid() && key.channel() instanceof SocketChannel) {
				if (((SocketChannel)key.channel()).getRemoteAddress().equals(client)) {
					((SocketChannel)key.channel())
							.write(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
				}
			}
		}
	}

	private void handleAccept(SelectionKey key, Selector selector) throws IOException {
		SocketChannel channel = ((ServerSocketChannel) key.channel()).accept();
		channel.configureBlocking(false);
		System.out.println("Client accepted. IP: " + channel.getRemoteAddress());

		channel.register(selector, SelectionKey.OP_READ, "some attach");
		channel.write(ByteBuffer.wrap("Hello user!\n".getBytes(StandardCharsets.UTF_8)));
		channel.write(ByteBuffer.wrap("Enter --help for support info\n".getBytes(StandardCharsets.UTF_8)));
		sendName(channel,"");
	}

	public static void main(String[] args) throws IOException {
		new NioTelnetServer();
	}
}
