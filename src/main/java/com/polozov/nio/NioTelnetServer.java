package com.polozov.nio;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

public class NioTelnetServer {
	private static final String ROOT_PATH = "server";

	public static final String LS_COMMAND = "\tls    view all files and directories\n";
	public static final String MKDIR_COMMAND = "\tmkdir    create directory\n";
	public static final String CHANGE_NICKNAME = "\tnick    change nickname\n";

	private static final String ROOT_NOTIFY = "You are already in the root directory\n\r";

	private static final String DIRECTORY_DOESNT_EXIST = "Directory or file %s doesn't exist\n\r";
	private static final String DIRECTORY_ALREADY_EXIST = "Director or file %s already exist\n\r";
	private static final String NO_SUCH_FILE = "%s: no such" + " file or directory\n\r";
	private static final String DIR_NOT_EMPTY = "%s not empty\n\r";


	private final ByteBuffer buffer = ByteBuffer.allocate(512);

	private Map<SocketAddress, Client> clients = new HashMap<>();

	class Client {

		private Path currentPath;

		private String nickName;

		public Client(String nickName) {
			this.currentPath = Path.of(ROOT_PATH);
			this.nickName = nickName;
		}

		public Path getCurrentPath() {
			return currentPath;
		}

		public void setCurrentPath(Path currentPath) {
			this.currentPath = currentPath;
		}

		public String getNickName() {
			return nickName;
		}

		public void setNickName(String nickName) {
			this.nickName = nickName;
		}
	}

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
		String nickname = "";
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
				sendMessage(getFileList(client).concat("\n"), selector, client);
			} else if (command.startsWith("nick ")) {
				changeNickname(client, command);
			} else if (command.startsWith("cd ")) {
				replacePosition(selector, client, command);
			} else if (command.startsWith("mkdir ")) {
				createDirectory(selector, client, command);
			} else if (command.startsWith("rm ")) {
				remove(selector, client, command);
			} else if (command.startsWith("copy ")) {
				copy(selector, client, command);
			} else if (command.startsWith("cat ")) {
				showFile(selector, client, command);
			} else if (command.startsWith("touch ")) {
				createFile(selector, client, command);
			} else if ("exit".equals(command)) {
				System.out.println("Client logged out. IP: " + channel.getRemoteAddress());
				clients.remove(client);
				channel.close();
				return;
			}
		}
		sendName(channel);
	}

	private void createFile(Selector selector, SocketAddress client, String command) throws IOException {
		Client cl = clients.get(client);
		String dir = command.split(" ")[1];
		Path neededPath = Path.of(cl.getCurrentPath().toString(), dir);
		if (Files.exists(neededPath) && !Files.isDirectory(neededPath)) {
			sendMessage(String.format(DIRECTORY_ALREADY_EXIST, dir), selector, client);
		} else {
			Files.createFile(neededPath);
		}
	}

	private void showFile(Selector selector, SocketAddress client, String command) throws IOException {
		Client cl = clients.get(client);
		String dir = command.split(" ")[1];
		Path neededPath = Path.of(cl.getCurrentPath().toString(), dir);
		if (Files.exists(neededPath) && !Files.isDirectory(neededPath)) {
			Files.readAllLines(neededPath).forEach(row->{
				try {
					sendMessage(row, selector, client);
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		} else {
			sendMessage(String.format(DIRECTORY_DOESNT_EXIST, dir), selector, client);
		}
	}

	private void copy(Selector selector, SocketAddress client, String command) throws IOException {
		Client cl = clients.get(client);
		String arg[] = command.split(" ");
		if (arg.length != 3) return;
		Path sourcePath = Path.of(cl.getCurrentPath().toString(), arg[1]);
		Files.walkFileTree(sourcePath, new FileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				String fileName = dir.toString().substring(sourcePath.toString().length());
				Path targetPath = Path.of(cl.getCurrentPath().toString(), arg[2], fileName);
				Files.createDirectory(targetPath);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				String fileName = file.toString().substring(sourcePath.toString().length());
				Path targetPath = Path.of(cl.getCurrentPath().toString(), arg[2], fileName);
				Files.copy(file, targetPath);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private void remove(Selector selector, SocketAddress client, String command) throws IOException {
		Client cl = clients.get(client);
		String dir = command.split(" ")[1];
		Path neededPath = Path.of(cl.getCurrentPath().toString(), dir);
		try {
			Files.delete(neededPath);
		} catch (NoSuchFileException e) {
			sendMessage(String.format(NO_SUCH_FILE, dir), selector, client);
		} catch (DirectoryNotEmptyException e) {
			sendMessage(String.format(DIR_NOT_EMPTY, dir), selector, client);
		}
	}

	private void createDirectory(Selector selector, SocketAddress client, String command) throws IOException {
		Client cl = clients.get(client);
		String dir = command.split(" ")[1];
		Path neededPath = Path.of(cl.getCurrentPath().toString(), dir);
		if (Files.exists(neededPath)) {
			sendMessage(String.format(DIRECTORY_ALREADY_EXIST, dir), selector, client);
		} else {
			Files.createDirectory(neededPath);
		}
	}

	private void replacePosition(Selector selector, SocketAddress client, String command) throws IOException {
		String neededPath = command.split(" ")[1];
		Client cl = clients.get(client);
		Path tempPath = Path.of(cl.getCurrentPath().toString(), neededPath);
		if ("..".equals(neededPath)) {
			tempPath = cl.getCurrentPath().getParent();
			if (tempPath == null || !tempPath.toString().startsWith("server")) {
				sendMessage(ROOT_NOTIFY, selector, client);
			} else {
				cl.setCurrentPath(tempPath);
			}
		} else if ("~".equals(neededPath)) {
			cl.setCurrentPath(Path.of(ROOT_PATH));
		} else {
			if (tempPath.toFile().exists()) {
				cl.setCurrentPath(tempPath);
			} else {
				sendMessage(String.format(DIRECTORY_DOESNT_EXIST, neededPath), selector, client);
			}
		}
	}

	private void changeNickname(SocketAddress client, String command) throws IOException {
		String nickname = command.split(" ")[1];
		clients.get(client).setNickName(nickname);
		System.out.println(String.format("Client - %s changed nick on %s", client.toString(), nickname));
	}

	private void sendName(SocketChannel channel) throws IOException {
		Client client = clients.get(channel.getRemoteAddress());
		String nickname = client.getNickName();
		String currentPathString = client.getCurrentPath().toString().replace("server", "~");
		String message = String.format("%s>:%s$", nickname, currentPathString);
		channel.write(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
	}

	private String getFileList(SocketAddress client) throws IOException {
		return String.join(" ", new File(clients.get(client).getCurrentPath().toString()).list());
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

		clients.put(channel.getRemoteAddress(), new Client(channel.getRemoteAddress().toString()));
		sendName(channel);
	}

	public static void main(String[] args) throws IOException {
		new NioTelnetServer();
	}
}
