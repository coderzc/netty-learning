package com.zc.nettystu.socket_demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Set;

/**
 * @author: zhaocong
 */
public class NioSocketClient {

    private static final ByteBuffer sendBuffer = ByteBuffer.allocateDirect(1024);
    private static final ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(1024);

    private static final StringBuffer boardMsg = new StringBuffer();
    private static Selector selector = null;
    private static SocketChannel socketChannel = null;
    private static SelectionKey registerKey = null;
    private static Thread reactor = null;

    private static boolean isConnected() {
        return socketChannel != null && socketChannel.isConnected();
    }


    public static void main(String[] args) throws IOException {
//        byte[] bytes = Files.readAllBytes(Paths.get("/Users/zc/Downloads/YoudaoNote.dmg"));
//        System.out.println("文件读取完毕,文件大小："+bytes.length);


        // 连接完成监听客户端输入

        // 打开复用器
        // 打开select
        selector = Selector.open();

        // 开始连接
        connect();

        // 启动select线程处理
        reactor = new Thread(NioSocketClient::reactor);
        reactor.start();

        // 监听键盘输入
        Thread receiver = new Thread(NioSocketClient::listenUserInput);
        receiver.start();
    }

    private static void connect() {
        try {
            // 打开通道
            socketChannel = SocketChannel.open();

            //设置通道为非阻塞
            socketChannel.configureBlocking(false);

            //连接主机
            boolean connect = socketChannel.connect(new InetSocketAddress("127.0.0.1", 8888));

            //注册事件
//            /* TODO 唤醒select 这里不太明白
//             *   因为主线程select() 阻塞，将会导致下面的方法中，当前线程调用socketChannel.register() 由于获取不到锁也阻塞
//             *   所以要在调register之前把select()唤醒
//             *
//             * selector.wakeup();
//             * registerKey = socketChannel.register(selector, 0);
//             */

            if (connect) {
                registerKey = socketChannel.register(selector, SelectionKey.OP_READ);
            } else {
                registerKey = socketChannel.register(selector, SelectionKey.OP_CONNECT);
            }

//            /*
//            TODO 唤醒select
//             1、selector所在线程可能正正阻塞在select()上，
//             interestOps 的改变不会立即被selector感知，需要手动唤醒selector所在线程
//             2、另外 key.readyOps() 并不能输出实时的状态，只有select()函数返回时key.readyOps()里的值才会被更新
//             即：jdk不会帮你把key.readyOps()归零 [readyOps()、selectedKeys()] 都是这样
//             selector.wakeup();
//             */



        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void reconnection(){
        //清除旧的连接
        registerKey.cancel();
        try {
            socketChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //终止旧的select线程
        reactor.interrupt();

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 开始连接
        connect();

        // 启动select线程处理
        reactor = new Thread(NioSocketClient::reactor);
        reactor.start();
    }

    private static void reactor() {
        try {
            // handler socket data
            while (!Thread.interrupted()) {
                int count = -2;
                while ((count = selector.select(0L)) > 0) {
//                System.out.println(count+"---");
                    Set<SelectionKey> keys = selector.selectedKeys();
//                if (keys == null || keys.size() == 0) {
//                    continue;
//                }
                    System.out.println("selector is activity，keySize：" + keys.size());

                    for (SelectionKey key : keys) {

                        // 判断连接状态
                        if (key.isValid() && key.isConnectable()) {
                            System.out.println("isConnectable");

                            //TODO isConnectable后，应该移除 OP_CONNECT 事件，否则在finishConnect成功后，select() 将一直返回0 ，CPU空转
                            key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT);


                            SocketChannel socketChannel = (SocketChannel) key.channel();
                            boolean finishConnect = false;
                            try {
                                finishConnect = socketChannel.finishConnect();
                            } catch (Exception e) {
                                finishConnect = false;
                            }
                            // 完成连接
                            if (finishConnect) {
                                System.out.println("已经连接到服务器");
                                // 注册读事件
                                socketChannel.register(selector, SelectionKey.OP_READ);

                                // 将断网前的数据发出
                            synchronized (boardMsg){
                                if (boardMsg.length() != 0) {
                                    sendBuffer.put((boardMsg + "\n").getBytes());
                                    registerKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);//添加写事件

                                    boardMsg.setLength(0);
                                }
                            }

                            } else {
                                System.out.println("连接失败尝试重连。。。");


                                /**
                                 * 仅重试，不销毁select线程
                                 */
                                //清除旧的连接
                                key.cancel();
                                try {
                                    socketChannel.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                                Thread.sleep(1500);
                                // 重新连接
                                connect();
                            }

                        }
                        // 接受来自服务器的响应
                        else if (key.isReadable()) {
                            System.out.println("isReadable");
                            receive(key);
                        }
                        // 实际上只要注册了关心写操作，这个操作就一直被激活
                        else if (key.isWritable()) {
                            System.out.println("isWritable");
                            send(key);
                        }


                    }

                    keys.clear();
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    private static void receive(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        int read = socketChannel.read(receiveBuffer);

        if (read > 0) {
            receiveBuffer.flip();
            String receiveData = Charset.forName("UTF-8").decode(receiveBuffer).toString();
            System.out.println("receive server message--->" + receiveData);
            receiveBuffer.clear();
        }

        // read == -1 代表服务端已经断开
        if (read == -1) {
            System.out.println("server already close.");
            // 反注册channel
            key.cancel();
            // 关闭socket
            socketChannel.close();
        }


    }


    private static void send(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        synchronized (sendBuffer) {
            sendBuffer.flip(); //设置写
            while (sendBuffer.hasRemaining()) {
                int writed = socketChannel.write(sendBuffer);
                System.out.println("writed byte is：" + writed);
            }
            sendBuffer.compact();

            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);//取消写事件
        }
    }


    private static void listenUserInput() {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
        try {
            String msg;
            while ((msg = bufferedReader.readLine()) != null) {
                System.out.println("Thread.getAllStackTraces:"+Thread.getAllStackTraces().keySet());


                if (!isConnected()) {
                    System.out.println("发送失败，连接已经断开，尝试重连。。。");

                    // 留言
                    synchronized (boardMsg) {
                        boardMsg.append(msg).append("\n");
                    }

                    if(socketChannel!=null && !socketChannel.isConnectionPending()){// 当前没有正在尝试连接，则主动触发重连
                        // 启动重连
                        reconnection();
                    }else {
                        System.out.println("已经尝试重连，请勿重复触发。。。");
                    }

                    continue;
                }

                synchronized (sendBuffer) {
                    sendBuffer.put((msg + "\n").getBytes());
                    registerKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);//添加写事件

                    /*
                    TODO
                     1、selector所在线程可能正正阻塞在select()上，
                     interestOps 的改变不会立即被selector感知，需要手动唤醒selector所在线程
                     2、另外 key.readyOps() 并不能输出实时的状态，只有select()函数返回时key.readyOps()里的值才会被更新
                     即：jdk不会帮你把key.readyOps()归零 [readyOps()、selectedKeys()] 都是这样
                     */
                    selector.wakeup();

                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
