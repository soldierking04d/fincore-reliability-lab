import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 防御实验：先核对完整业务意图，再允许签名。所有密钥均启动时临时生成，不是钱包密钥。
 * 使用真实 Ed25519 验签演示审批绑定，不是 EIP-7702/secp256k1/RLP 实现，也不生成链上交易。
 * 本进程中的 Set 和预算只服务并发测试，绝不能用作生产资金账本或跨实例额度权威。
 */
public final class SigningIntentGuard {
    private static final BigInteger SINGLE_LIMIT = BigInteger.valueOf(10000);
    private static final BigInteger ROLLING_LIMIT = BigInteger.valueOf(20000);

    /** 重要字段全部进入规范编码，避免审批“给A转1”后实际签成“给B转100”。 */
    record Intent(String requestId, String operation, long chainId, String wallet,
                  String asset, String recipient, BigInteger amountMinor,
                  String callDataHash, long nonce, long expiresAt, int policyVersion, int sessionEpoch) {}

    /** 防止调用方持有 byte[] 引用，在验签前后改动审批内容。 */
    record Approval(byte[] intentHash, byte[] signature) {
        Approval { intentHash = intentHash.clone(); signature = signature.clone(); }
        public byte[] intentHash() { return intentHash.clone(); }
        public byte[] signature() { return signature.clone(); }
    }

    static final class Denied extends RuntimeException {
        Denied(String code) { super(code); }
    }

    static final class Guard {
        private final KeyPair approvalKey;
        private final KeyPair signingKey;
        private final Set<String> consumed = new HashSet<>();
        private BigInteger reserved = BigInteger.ZERO;
        private boolean paused;

        Guard() throws Exception {
            var generator = KeyPairGenerator.getInstance("Ed25519");
            approvalKey = generator.generateKeyPair();
            signingKey = generator.generateKeyPair();
        }

        /** 教学中的可信审批方；生产必须独立于被调用容器的身份、网络和发布权限。 */
        Approval approve(Intent intent, long now) throws Exception {
            validate(intent, now);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(encode(intent));
            return new Approval(hash, signature(approvalKey, hash));
        }

        /**
         * 同一实例用锁把防重和累计预算纳入一次决定，避免并发检查后同时穿过限额。
         * 生产应在持久化意图/额度预占事务中实现相同原子性，并区分签名、广播和最终确认。
         */
        synchronized byte[] sign(Intent intent, Approval approval, long now) throws Exception {
            if (paused) throw new Denied("SIGNING_PAUSED");
            validate(intent, now);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(encode(intent));
            if (!MessageDigest.isEqual(hash, approval.intentHash())) throw new Denied("INTENT_MISMATCH");
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(approvalKey.getPublic());
            verifier.update(hash);
            boolean valid;
            try { valid = verifier.verify(approval.signature()); } catch (Exception exception) { valid = false; }
            if (!valid) throw new Denied("APPROVAL_SIGNATURE_INVALID");
            if (consumed.contains(intent.requestId())) throw new Denied("APPROVAL_REPLAY");
            BigInteger next = reserved.add(intent.amountMinor());
            if (next.compareTo(ROLLING_LIMIT) > 0) throw new Denied("ROLLING_LIMIT");
            byte[] signed = signature(signingKey, encode(intent));
            consumed.add(intent.requestId());
            reserved = next;
            return signed;
        }

        synchronized void pause() { paused = true; }

        boolean verifyResult(Intent intent, byte[] bytes) throws Exception {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(signingKey.getPublic()); verifier.update(encode(intent));
            return verifier.verify(bytes);
        }
    }

    private static void validate(Intent i, long now) {
        if (!"WITHDRAW".equals(i.operation())) throw new Denied("OPERATION_DENIED");
        if (i.chainId() != 1 || !"lab-wallet".equals(i.wallet()) || !"LAB".equals(i.asset())) throw new Denied("DOMAIN_DENIED");
        if (!"approved-recipient".equals(i.recipient())) throw new Denied("RECIPIENT_DENIED");
        if (!"empty-calldata".equals(i.callDataHash())) throw new Denied("CALLDATA_DENIED");
        if (i.amountMinor() == null || i.amountMinor().signum() <= 0 || i.amountMinor().compareTo(SINGLE_LIMIT) > 0) throw new Denied("AMOUNT_DENIED");
        if (i.nonce() < 0 || i.requestId() == null || i.requestId().isBlank() || i.requestId().length() > 80) throw new Denied("REQUEST_INVALID");
        if (i.expiresAt() <= now || i.expiresAt() - now > 300) throw new Denied("APPROVAL_EXPIRED");
        if (i.policyVersion() != 7 || i.sessionEpoch() != 3) throw new Denied("POLICY_OR_EPOCH_STALE");
    }

    /** 长度前缀编码 + 明确域标签；此编码不兼容以太坊，只证明服务端意图绑定。 */
    private static byte[] encode(Intent i) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeUTF("FINCORE_INTENT_V1"); out.writeUTF(i.requestId()); out.writeUTF(i.operation());
            out.writeLong(i.chainId()); out.writeUTF(i.wallet()); out.writeUTF(i.asset()); out.writeUTF(i.recipient());
            out.writeUTF(i.amountMinor().toString()); out.writeUTF(i.callDataHash()); out.writeLong(i.nonce());
            out.writeLong(i.expiresAt()); out.writeInt(i.policyVersion()); out.writeInt(i.sessionEpoch());
        }
        return buffer.toByteArray();
    }

    private static byte[] signature(KeyPair key, byte[] bytes) throws Exception {
        var signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(bytes);
        return signer.sign();
    }

    private static Intent normal(String id, long nonce, int amount) {
        return new Intent(id,"WITHDRAW",1,"lab-wallet","LAB","approved-recipient",BigInteger.valueOf(amount),"empty-calldata",nonce,1100,7,3);
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static int passed;
    private static void check(String name, Checked body) throws Exception { body.run(); passed++; }
    private static void denied(Checked body, String expected) throws Exception {
        try { body.run(); throw new AssertionError("未拒绝：" + expected); }
        catch (Denied exception) { if (!exception.getMessage().equals(expected)) throw new AssertionError(exception.getMessage()); }
    }

    public static void main(String[] args) throws Exception {
        Guard guard = new Guard(); Intent good = normal("legitimate",1,5000); Approval approved = guard.approve(good,1000);
        check("真实验签与合法签名",()->{ if(!guard.verifyResult(good,guard.sign(good,approved,1000))) throw new AssertionError(); });
        check("重放拒绝",()->denied(()->guard.sign(good,approved,1000),"APPROVAL_REPLAY"));
        check("金额篡改",()->denied(()->guard.sign(normal("legitimate",1,6000),approved,1000),"INTENT_MISMATCH"));
        check("nonce篡改",()->denied(()->guard.sign(normal("legitimate",2,5000),approved,1000),"INTENT_MISMATCH"));
        check("审批伪造",()->{Intent n=normal("forged",3,1000);byte[] hash=MessageDigest.getInstance("SHA-256").digest(encode(n));denied(()->guard.sign(n,new Approval(hash,new byte[64]),1000),"APPROVAL_SIGNATURE_INVALID");});
        check("过期拒绝",()->denied(()->guard.sign(good,approved,1100),"APPROVAL_EXPIRED"));
        check("委托操作默认拒绝",()->denied(()->guard.approve(new Intent("x","DELEGATE",1,"lab-wallet","LAB","approved-recipient",BigInteger.ONE,"empty-calldata",1,1100,7,3),1000),"OPERATION_DENIED"));
        check("跨链拒绝",()->denied(()->guard.approve(new Intent("x","WITHDRAW",137,"lab-wallet","LAB","approved-recipient",BigInteger.ONE,"empty-calldata",1,1100,7,3),1000),"DOMAIN_DENIED"));
        check("地址替换拒绝",()->denied(()->guard.approve(new Intent("x","WITHDRAW",1,"lab-wallet","LAB","unreviewed-recipient",BigInteger.ONE,"empty-calldata",1,1100,7,3),1000),"RECIPIENT_DENIED"));
        check("隐藏调用拒绝",()->denied(()->guard.approve(new Intent("x","WITHDRAW",1,"lab-wallet","LAB","approved-recipient",BigInteger.ONE,"unknown-call",1,1100,7,3),1000),"CALLDATA_DENIED"));
        check("过期策略拒绝",()->denied(()->guard.approve(new Intent("x","WITHDRAW",1,"lab-wallet","LAB","approved-recipient",BigInteger.ONE,"empty-calldata",1,1100,6,3),1000),"POLICY_OR_EPOCH_STALE"));
        check("被撤销会话代次拒绝",()->denied(()->guard.approve(new Intent("x","WITHDRAW",1,"lab-wallet","LAB","approved-recipient",BigInteger.ONE,"empty-calldata",1,1100,7,2),1000),"POLICY_OR_EPOCH_STALE"));
        check("单笔超限拒绝",()->denied(()->guard.approve(normal("large",9,10001),1000),"AMOUNT_DENIED"));
        check("防御性复制",()->{byte[] original=approved.intentHash();original[0]^=1;if(MessageDigest.isEqual(original,approved.intentHash()))throw new AssertionError();});
        check("熔断优先于旧审批",()->{guard.pause();denied(()->guard.sign(normal("after-pause",10,1),approved,1000),"SIGNING_PAUSED");});
        check("并发拆单累计预占",()->{
            Guard concurrent = new Guard(); var latch = new CountDownLatch(1); var ok = new AtomicInteger(); var rejected = new AtomicInteger(); var errors = new AtomicInteger();
            Thread[] threads = new Thread[3];
            for(int index=0;index<3;index++) { Intent i=normal("split-"+index,index,7500);Approval a=concurrent.approve(i,1000);
                threads[index]=new Thread(()->{try{latch.await();concurrent.sign(i,a,1000);ok.incrementAndGet();}catch(Denied e){if(e.getMessage().equals("ROLLING_LIMIT"))rejected.incrementAndGet();else errors.incrementAndGet();}catch(Exception e){errors.incrementAndGet();}}); threads[index].start(); }
            latch.countDown();for(Thread t:threads)t.join(3000);
            if(ok.get()!=2||rejected.get()!=1||errors.get()!=0)throw new AssertionError("累计限额并发失败");
        });
        System.out.println("{\"passed\":"+passed+",\"cryptography\":\"Ed25519\",\"walletConnected\":false,\"chainTransactionBroadcast\":false,\"concurrentAccepted\":2,\"concurrentRejected\":1}");
    }
}
