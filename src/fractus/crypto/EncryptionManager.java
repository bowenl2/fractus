package fractus.crypto;


import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import org.apache.log4j.Logger;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.agreement.kdf.DHKDFParameters;
import org.bouncycastle.crypto.agreement.kdf.ECDHKEKGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.asymmetric.ec.KeyPairGenerator;
import org.bouncycastle.jce.spec.ECParameterSpec;

import fractus.main.BinaryUtil;
import fractus.net.ProtocolBuffer;

public class EncryptionManager
implements KeyDerivationEngine {
	public final static String ELLIPTIC_CURVE = "secp521r1";
	private static Logger log;
	private KeyPair keyPair;
	private String encodingType;
	ECDHBasicAgreement agreement;
	ProtocolBuffer.CipherCapabilities cipherCapabilities;
	private static EncryptionManager instance;
	public static EncryptionManager getInstance() {
		return instance;
	}
	static {
		log = Logger.getLogger(EncryptionManager.class.getName());
		instance = new EncryptionManager();
	}

	private EncryptionManager() {
		try {
			this.keyPair = generateKey();
		} catch (GeneralSecurityException e) {
			log.error("Cannot generate key",e);
			throw new RuntimeException(e);
		}
		encodingType = keyPair.getPublic().getFormat();
		ECPrivateKey privKey = (ECPrivateKey) keyPair.getPrivate();
		agreement = new ECDHBasicAgreement();
		ECParameterSpec spec = privKey.getParameters();
		ECDomainParameters dp = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(),
				spec.getH(), spec.getSeed());
		ECPrivateKeyParameters pkp = new ECPrivateKeyParameters(privKey.getD(), dp);
		agreement.init(pkp);
		generateCapabilityProtocolBuffer();
		log.info("EncryptionManager constructed");
	}

	private KeyPair generateKey()
	throws GeneralSecurityException {
		log.info("Initializing keypair generator for EC secp521r1");
		ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp521r1");
		KeyPairGenerator g = new org.bouncycastle.jce.provider.asymmetric.ec.KeyPairGenerator.ECDH();
		g.initialize(ecSpec, new SecureRandom());
		log.info("Generating EC secp521r1 Key Pair");
		KeyPair pair = g.generateKeyPair();
		log.info("ECDH KeyPair generated with public key:" + BinaryUtil.encodeData(pair.getPublic().getEncoded()));
		return pair;
	}
	
	private void generateCapabilityProtocolBuffer() {
		this.cipherCapabilities = ProtocolBuffer.CipherCapabilities.newBuilder()
		.addCipherSuites(ProtocolBuffer.CipherSuite.newBuilder()
		.setCipherAlgorithm("AES")
		.setCipherMode("GCM")
		.setCipherKeySize(256)
		.setKeyDerivationFunction("KDF2")
		.setPublicKeyType("EC")
		.setSecretEstablishmentAlgorithm("ECDH")).build();
	}
	
	public String getEncodingFormat() {
		return encodingType;
	}

	public ECPublicKey getPublicKey() {
		return (ECPublicKey)keyPair.getPublic();
	}
	
	public byte[] getEncodedPublicKey() {
		return keyPair.getPublic().getEncoded();
	}

	public SecretKeySpec deriveKey(PublicKey publicKey) {
		if (!(publicKey instanceof ECPublicKey)) {
			throw new IllegalArgumentException("pubkey must be an ECPublicKey");
		}
		ECPublicKey ecPublicKey = (ECPublicKey)publicKey;
		ECParameterSpec parameterSpec = ecPublicKey.getParameters();
		ECDomainParameters publicDomainParameters = new ECDomainParameters(
				parameterSpec.getCurve(), parameterSpec.getG(), parameterSpec.getN(),
				parameterSpec.getH(), parameterSpec.getSeed());
		ECPublicKeyParameters publicKeyParameters = new ECPublicKeyParameters(ecPublicKey.getQ(),
				publicDomainParameters);
		BigInteger sharedSecret = agreement.calculateAgreement(publicKeyParameters);
		ECDHKEKGenerator kdf = new ECDHKEKGenerator(new SHA256Digest());
		DERObjectIdentifier kdfObjectIdentifier = NISTObjectIdentifiers.id_aes256_GCM;
		DHKDFParameters dhParameters = new DHKDFParameters(kdfObjectIdentifier, 256, sharedSecret.toByteArray());
		kdf.init(dhParameters);
		byte[] keymaterial = new byte[256/8];
		kdf.generateBytes(keymaterial, 0, keymaterial.length);
		log.debug("Derived key with KDF for AES/GCM/256");
		return new SecretKeySpec(keymaterial, 0, keymaterial.length, "AES");
	}
	
	public ProtocolBuffer.CipherCapabilities getCipherCapabilities() {
		return this.cipherCapabilities;
	}
}
