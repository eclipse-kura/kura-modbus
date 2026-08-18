@Library('add-ons-shared-libs@develop') _

node {
    continuousIntegrationPipeline(
        buildType: "deploy",
        sonar: [
            enable: true,
            projectKey: "eclipse-kura_kura-modbus",
            tokenId: "sonarcloud-token-kura-modbus",
            exclusions: "tests/**/*.java"
        ],
    )
}
