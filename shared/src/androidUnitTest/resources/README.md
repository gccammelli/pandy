The loopback TLS tests generate a disposable self-signed server identity with the
JDK's keytool and delete its temporary keystore immediately after loading it.
No private key or printer credentials are committed. Production retains its Bambu
CA and printer serial checks.
