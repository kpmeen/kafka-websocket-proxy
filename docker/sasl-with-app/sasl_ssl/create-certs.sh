#!/bin/bash

#set -o nounset \
#    -o errexit \
#    -o verbose \
#    -o xtrace

if [[ "$1" = "--clean" ]]; then
  echo "Creating certs..."
  cleanup=true;
else
  echo "NOT creating certs..."
  cleanup=false;
fi;

BASE_HOSTNAME="test.scalytica.net"

if [[ "$cleanup" = "true" ]]; then
  echo "Removing previously generated certificates"
  rm -f *.jks *_creds 

  echo "Generating CA..."
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

  echo "Generating key stores and certs..."
  # Loop over the "hosts" below to generate key stores and certs
  for i in broker1 client
  do
    echo $i
    # Create key stores
    keytool \
      -genkey \
      -noprompt \
      -alias $i \
      -dname "CN=$i.$BASE_HOSTNAME/OU=TEST/O=SCALYTICA/L=Heggedal/S=Akershus/C=NO" \
      -keystore $i.keystore.jks \
      -keyalg RSA \
      -storepass scalytica \
      -keypass scalytica

    # Create CSR, sign the key and import back into keystore
    keytool \
      -certreq \
      -alias $i \
      -keystore $i.keystore.jks \
      -file $i.csr \
      -storepass scalytica \
      -keypass scalytica

    openssl x509 \
      -req \
      -CA test-ca-1.crt \
      -CAkey test-ca-1.key \
      -in $i.csr \
      -out $i-ca1-signed.crt \
      -days 9999 \
      -CAcreateserial \
      -passin pass:scalytica

    keytool \
      -import \
      -alias CARoot \
      -file test-ca-1.crt \
      -keystore $i.keystore.jks \
      -storepass scalytica \
      -keypass scalytica << EOF
yes
EOF

    keytool \
      -import \
      -alias $i \
      -file $i-ca1-signed.crt \
      -keystore $i.keystore.jks \
      -storepass scalytica \
      -keypass scalytica << EOF
yes
EOF

    # Create truststore and import the CA cert.
    keytool \
      -import \
      -alias CARoot \
      -file test-ca-1.crt \
      -keystore $i.truststore.jks \
      -storepass scalytica \
      -keypass scalytica << EOF
yes
EOF

    echo "scalytica" > ${i}_sslkey_creds
    echo "scalytica" > ${i}_keystore_creds
    echo "scalytica" > ${i}_truststore_creds
  done

  echo "Cleaning up..."

  rm *.csr *.srl *.key *.crt
fi;
