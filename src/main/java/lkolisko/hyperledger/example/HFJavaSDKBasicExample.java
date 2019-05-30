package lkolisko.hyperledger.example;

import org.apache.log4j.Logger;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.hyperledger.fabric.sdk.exception.TransactionException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * <h1>HFJavaSDKBasicExample</h1>
 * <p>
 * Simple example showcasing basic fabric-ca and fabric actions.
 * The demo required fabcar fabric up and running.
 * <p>
 * The demo shows
 * <ul>
 * <li>connecting to fabric-ca</li>
 * <li>enrolling admin to get new key-pair, certificate</li>
 * <li>registering and enrolling a new user using admin</li>
 * <li>creating HF client and initializing channel</li>
 * <li>invoking chaincode query</li>
 * </ul>
 *
 * @author main
 */
public class HFJavaSDKBasicExample {

    private static final Logger log = Logger.getLogger(HFJavaSDKBasicExample.class);


    public static void main(String[] args) throws Exception {

        String userName = "user15";

        EnrollDemo(userName);

        ClientDemo(userName);
    }

    public static void EnrollDemo(String userName) throws Exception {
        // 基础数据
        String caUrl = "https://ca.org1.example.com:7054";
        String caName = "ca-org1";
        String pemFile = "ca.org1.example.com.pem";

        AppUser user = tryDeserialize(userName);
        if (user != null) {
            return;
        }

        // 构造一个属性，这个属性在 CA 用户登录的时候会使用
        Properties properties = new Properties();
        properties.put("pemFile", pemFile);
        properties.put("allowAllHostNames", "true");

        // 创建一个 ca-client
        HFCAClient hfcaClient = HFCAClient.createNewInstance(caName, caUrl, properties);

        // 创建加密组件
        CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();

        // ca-client 装配加密组件
        hfcaClient.setCryptoSuite(cryptoSuite);

        // 使用 admin 登录 CA 服务器
        Enrollment adminEnrollment = hfcaClient.enroll("admin", "adminpw");

        // 创建 admin 的信息对象
        AppUser admin = new AppUser("admin", "org1", "Org1MSP", adminEnrollment);

        // 将 admin 的信息对象写入本地文件
        serialize(admin);

        // 注册一个新的用户
        RegistrationRequest rr = new RegistrationRequest(userName, "org1");
        String enrollmentSecret = hfcaClient.register(rr, admin);

        // 登录新用户
        Enrollment newUserEnrollment = hfcaClient.enroll(userName, enrollmentSecret);

        // 创建新用户的信息对象
        AppUser newUser = new AppUser(userName, "org1", "Org1MSP", newUserEnrollment);

        // 将新用户的信息对象写入本地文件
        serialize(newUser);
    }

    public static void ClientDemo(String userName) throws Exception {

        // 基础数据
        String channelName = "mychannel";

        String peer_name = "peer0.org1.example.com";
        String peer_url = "grpcs://peer0.org1.example.com:7051";
        String peer_pemFile = "peer0.org1.example.com.pem";

        String orderer_name = "orderer.example.com";
        String orderer_url = "grpcs://orderer.example.com:7050";
        String orderer_pemFile = "orderer.example.com.pem";

        // 读取 admin 的信息
        AppUser user = tryDeserialize(userName);

        // 创建加密组件
        CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();

        // 创建 Fabric 的客户端
        HFClient hfClient = HFClient.createNewInstance();

        // 装配加密组件
        hfClient.setCryptoSuite(cryptoSuite);

        // 装配用户信息
        hfClient.setUserContext(user);

        // 创建 peer
        Properties peer_properties = new Properties();
        peer_properties.put("pemFile", peer_pemFile);
        Peer peer = hfClient.newPeer(peer_name, peer_url, peer_properties);

        // 创建 orderer
        Properties orderer_properties = new Properties();
        orderer_properties.put("pemFile", orderer_pemFile);
        // 我不清楚以下两行的具体的意思是什么
        // orderer_properties.setProperty("sslProvider", "openSSL");
        // orderer_properties.setProperty("negotiationType", "TLS");
        Orderer orderer = hfClient.newOrderer(orderer_name, orderer_url, orderer_properties);

        // 创建 channel，channel 一旦创建就是保存在 client 中
        Channel channel  = hfClient.newChannel(channelName);

        // 装配 peer 和 orderer
        channel.addPeer(peer);
        channel.addOrderer(orderer);

        // 初始化 channel
        channel.initialize();

        queryBlockChain(hfClient);
    }

    /**
     * Invoke blockchain query
     *
     * @param client The HF Client
     * @throws ProposalException
     * @throws InvalidArgumentException
     */
    static void queryBlockChain(HFClient client) throws ProposalException, InvalidArgumentException {
        // get channel instance from client
        Channel channel = client.getChannel("mychannel");
        // create chaincode request
        QueryByChaincodeRequest qpr = client.newQueryProposalRequest();
        // build cc id providing the chaincode name. Version is omitted here.
        ChaincodeID fabcarCCId = ChaincodeID.newBuilder().setName("fabcar").build();
        qpr.setChaincodeID(fabcarCCId);
        // CC function to be called
        qpr.setFcn("queryAllCars");
        Collection<ProposalResponse> res = channel.queryByChaincode(qpr);
        // display response
        for (ProposalResponse pres : res) {
            String stringResponse = new String(pres.getChaincodeActionResponsePayload());
            log.info(stringResponse);
        }
    }

    // user serialization and deserialization utility functions
    // files are stored in the base directory

    /**
     * Serialize AppUser object to file
     *
     * @param appUser The object to be serialized
     * @throws IOException
     */
    static void serialize(AppUser appUser) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(
                Paths.get(appUser.getName() + ".jso")))) {
            oos.writeObject(appUser);
        }
    }

    /**
     * Deserialize AppUser object from file
     *
     * @param name The name of the user. Used to build file name ${name}.jso
     * @return
     * @throws Exception
     */
    static AppUser tryDeserialize(String name) throws Exception {
        if (Files.exists(Paths.get(name + ".jso"))) {
            return deserialize(name);
        }
        return null;
    }

    static AppUser deserialize(String name) throws Exception {
        try (ObjectInputStream decoder = new ObjectInputStream(
                Files.newInputStream(Paths.get(name + ".jso")))) {
            return (AppUser) decoder.readObject();
        }
    }
}
