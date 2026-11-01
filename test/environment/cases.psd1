@{
    SchemaVersion = 1
    ScenarioGroup = 'manual'
    Cases = @('tls-trusted', 'tls-untrusted', 'mysql', 'postgresql')
    Topology = @('bukkit', 'fabric')
    FabricRuntime = @{
        LauncherUrl = 'https://meta.fabricmc.net/v2/versions/loader/1.21.11/0.19.3/1.1.2/server/jar'
        LauncherFile = 'fabric-server-launch-1.21.11-0.19.3.jar'
        LauncherSha256 = 'B855772F5A5D591262260E8ED3C218419366D74C3ACFC38409F4A376F167051B'
        ApiFile = 'fabric-api-0.141.6+1.21.11.jar'
        ApiUrl = 'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.141.6%2B1.21.11/fabric-api-0.141.6%2B1.21.11.jar'
        ApiSha256 = 'BDFF7FD7E220085CFAD2FF9B1F40DDE6534AE0B96CF378F97A374BC54CB9ED0F'
        McVersion = '1.21.11'
    }
    RequiredAssertions = @{
        'tls-trusted' = @('ENV-TLS-TRUSTED-AUTH', 'ENV-TLS-TRUSTED-TO-FABRIC', 'ENV-TLS-TRUSTED-FROM-FABRIC')
        'tls-untrusted' = @('ENV-TLS-UNTRUSTED-CONTROL', 'ENV-TLS-UNTRUSTED-CERTIFICATE', 'ENV-TLS-UNTRUSTED-NO-AUTH', 'ENV-TLS-UNTRUSTED-NO-DELIVERY')
        mysql = @('ENV-MYSQL-GAME-DELIVERY', 'ENV-MYSQL-STORED', 'ENV-MYSQL-RESTARTED', 'ENV-MYSQL-SAME-ROW', 'ENV-MYSQL-POST-RESTART-GAME')
        postgresql = @('ENV-POSTGRES-GAME-DELIVERY', 'ENV-POSTGRES-STORED', 'ENV-POSTGRES-RESTARTED', 'ENV-POSTGRES-SAME-ROW', 'ENV-POSTGRES-POST-RESTART-GAME')
    }
}
