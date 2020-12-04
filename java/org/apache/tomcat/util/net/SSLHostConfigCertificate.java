/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.openssl.ciphers.Authentication;
import org.apache.tomcat.util.res.StringManager;

import javax.management.ObjectName;
import javax.net.ssl.X509KeyManager;
import java.io.IOException;
import java.io.Serializable;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SSLHostConfigCertificate implements Serializable {

    public static final Type DEFAULT_TYPE = Type.UNDEFINED;
    static final String DEFAULT_KEYSTORE_PROVIDER =
            System.getProperty("javax.net.ssl.keyStoreProvider");
    static final String DEFAULT_KEYSTORE_TYPE =
            System.getProperty("javax.net.ssl.keyStoreType", "JKS");
    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(SSLHostConfigCertificate.class);
    private static final StringManager sm = StringManager.getManager(SSLHostConfigCertificate.class);
    // Common
    private final SSLHostConfig sslHostConfig;
    private final Type type;
    // Internal
    private ObjectName oname;
    // OpenSSL can handle multiple certs in a single config so the reference to
    // the context is at the virtual host level. JSSE can't so the reference is
    // held here on the certificate.
    private transient SSLContext sslContext;
    private String certificateKeyPassword = null;

    // JSSE
    private String certificateKeyAlias;
    private String certificateKeystorePassword = "changeit";
    private String certificateKeystoreFile = System.getProperty("user.home") + "/.keystore";
    private String certificateKeystoreProvider = DEFAULT_KEYSTORE_PROVIDER;
    private String certificateKeystoreType = DEFAULT_KEYSTORE_TYPE;
    private transient KeyStore certificateKeystore = null;
    private transient X509KeyManager certificateKeyManager = null;

    // OpenSSL
    private String certificateChainFile;
    private String certificateFile;
    private String certificateKeyFile;

    // Certificate store type
    private StoreType storeType = null;

    public SSLHostConfigCertificate() {
        this(null, Type.UNDEFINED);
    }

    public SSLHostConfigCertificate(SSLHostConfig sslHostConfig, Type type) {
        this.sslHostConfig = sslHostConfig;
        this.type = type;
    }


    public SSLContext getSslContext() {
        return sslContext;
    }


    public void setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }


    public SSLHostConfig getSSLHostConfig() {
        return sslHostConfig;
    }


    // Internal

    public ObjectName getObjectName() {
        return oname;
    }


    public void setObjectName(ObjectName oname) {
        this.oname = oname;
    }


    // Common

    public Type getType() {
        return type;
    }


    public String getCertificateKeyPassword() {
        return certificateKeyPassword;
    }


    public void setCertificateKeyPassword(String certificateKeyPassword) {
        this.certificateKeyPassword = certificateKeyPassword;
    }


    // JSSE

    public String getCertificateKeyAlias() {
        return certificateKeyAlias;
    }

    public void setCertificateKeyAlias(String certificateKeyAlias) {
        sslHostConfig.setProperty(
                "Certificate.certificateKeyAlias", SSLHostConfig.Type.JSSE);
        this.certificateKeyAlias = certificateKeyAlias;
    }

    public String getCertificateKeystoreFile() {
        return certificateKeystoreFile;
    }

    public void setCertificateKeystoreFile(String certificateKeystoreFile) {
        sslHostConfig.setProperty(
                "Certificate.certificateKeystoreFile", SSLHostConfig.Type.JSSE);
        setStoreType("Certificate.certificateKeystoreFile", StoreType.KEYSTORE);
        this.certificateKeystoreFile = certificateKeystoreFile;
    }

    public String getCertificateKeystorePassword() {
        return certificateKeystorePassword;
    }

    public void setCertificateKeystorePassword(String certificateKeystorePassword) {
        sslHostConfig.setProperty(
                "Certificate.certificateKeystorePassword", SSLHostConfig.Type.JSSE);
        setStoreType("Certificate.certificateKeystorePassword", StoreType.KEYSTORE);
        this.certificateKeystorePassword = certificateKeystorePassword;
    }

    public String getCertificateKeystoreProvider() {
        return certificateKeystoreProvider;
    }

    public void setCertificateKeystoreProvider(String certificateKeystoreProvider) {
        sslHostConfig.setProperty(
                "Certificate.certificateKeystoreProvider", SSLHostConfig.Type.JSSE);
        setStoreType("Certificate.certificateKeystoreProvider", StoreType.KEYSTORE);
        this.certificateKeystoreProvider = certificateKeystoreProvider;
    }

    public String getCertificateKeystoreType() {
        return certificateKeystoreType;
    }

    public void setCertificateKeystoreType(String certificateKeystoreType) {
        sslHostConfig.setProperty(
                "Certificate.certificateKeystoreType", SSLHostConfig.Type.JSSE);
        setStoreType("Certificate.certificateKeystoreType", StoreType.KEYSTORE);
        this.certificateKeystoreType = certificateKeystoreType;
    }

    public KeyStore getCertificateKeystore() throws IOException {
        KeyStore result = certificateKeystore;

        if (result == null && storeType == StoreType.KEYSTORE) {
            result = SSLUtilBase.getStore(getCertificateKeystoreType(),
                    getCertificateKeystoreProvider(), getCertificateKeystoreFile(),
                    getCertificateKeystorePassword());
        }

        return result;
    }

    public void setCertificateKeystore(KeyStore certificateKeystore) {
        this.certificateKeystore = certificateKeystore;
    }

    public X509KeyManager getCertificateKeyManager() {
        return certificateKeyManager;
    }

    public void setCertificateKeyManager(X509KeyManager certificateKeyManager) {
        this.certificateKeyManager = certificateKeyManager;
    }


    // OpenSSL

    public String getCertificateChainFile() {
        return certificateChainFile;
    }

    public void setCertificateChainFile(String certificateChainFile) {
        setStoreType("Certificate.certificateChainFile", StoreType.PEM);
        this.certificateChainFile = certificateChainFile;
    }

    public String getCertificateFile() {
        return certificateFile;
    }

    public void setCertificateFile(String certificateFile) {
        setStoreType("Certificate.certificateFile", StoreType.PEM);
        this.certificateFile = certificateFile;
    }

    public String getCertificateKeyFile() {
        return certificateKeyFile;
    }

    public void setCertificateKeyFile(String certificateKeyFile) {
        setStoreType("Certificate.certificateKeyFile", StoreType.PEM);
        this.certificateKeyFile = certificateKeyFile;
    }

    private void setStoreType(String name, StoreType type) {
        if (storeType == null) {
            storeType = type;
        } else if (storeType != type) {
            log.warn(sm.getString("sslHostConfigCertificate.mismatch",
                    name, sslHostConfig.getHostName(), type, this.storeType));
        }
    }

    // Nested types

    public enum Type {

        UNDEFINED,
        RSA(Authentication.RSA),
        DSA(Authentication.DSS),
        EC(Authentication.ECDH, Authentication.ECDSA);

        private final Set<Authentication> compatibleAuthentications;

        private Type(Authentication... authentications) {
            compatibleAuthentications = new HashSet<>();
            if (authentications != null) {
                compatibleAuthentications.addAll(Arrays.asList(authentications));
            }
        }

        public boolean isCompatibleWith(Authentication au) {
            return compatibleAuthentications.contains(au);
        }
    }

    enum StoreType {
        KEYSTORE,
        PEM
    }
}
