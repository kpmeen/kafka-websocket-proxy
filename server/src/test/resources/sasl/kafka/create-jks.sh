#!/bin/bash

set -o nounset \
    -o errexit \
    -o verbose \
    -o xtrace

BASE_HOSTNAME="test.scalytica.net"

# Generate CA (Certificate Authority) key
openssl req \
  -new \
  -x509 \
  -keyout test-ca-1.key \
  -out test-ca-1.crt \
  -days 365 \
  -subj "/CN=ca1.$BASE_HOSTNAME/OU=TEST/O=SCALYTICA/L=Heggedal/S=Akershus/C=NO" \
  -passin pass:scalytica \
  -passout pass:scalytica

# WSPROXY

# Create key stores
keytool \
  -genkey \
  -noprompt \
  -alias wsproxy \
  -dname "CN=wsproxy.$BASE_HOSTNAME/OU=TEST/O=SCALYTICA/L=Heggedal/S=Akershus/C=NO" \
  -keystore $i.keystore.jks \
  -keyalg RSA \
  -storepass scalytica \
  -keypass scalytica

# Create CSR, sign the key and import back into keystore
keytool \
  -certreq \
  -alias wsproxy \
  -keystore wsproxy.keystore.jks \
  -file wsproxy.csr \
  -storepass scalytica \
  -keypass scalytica

openssl x509 \
  -req \
  -CA test-ca-1.crt \
  -CAkey test-ca-1.key \
  -in wsproxy.csr \
  -out wsproxy-ca1-signed.crt \
  -days 9999 \
  -CAcreateserial \
  -passin pass:scalytica

keytool \
  -import \
  -alias CARoot \
  -file test-ca-1.crt \
  -keystore wsproxy.keystore.jks \
  -storepass scalytica \
  -keypass scalytica << EOF
yes
EOF

keytool \
  -import \
  -alias wsproxy \
  -file wsproxy-ca1-signed.crt \
  -keystore wsproxy.keystore.jks \
  -storepass scalytica \
  -keypass scalytica << EOF
yes
EOF

# Create truststore and import the CA cert.
keytool \
  -import \
  -alias CARoot \
  -file test-ca-1.crt \
  -keystore wsproxy.truststore.jks \
  -storepass scalytica \
  -keypass scalytica << EOF
yes
EOF

echo "Cleaning up..."

rm *.csr *.srl *.key *.crt