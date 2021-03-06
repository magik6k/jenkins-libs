// Resolve takes a website and record, and makes it into a URL that can be
// resolved with `ipfs resolve $DOMAIN`, removing _dnslink if needed
def Resolve(opts = []) {
  assert opts['website'] : 'You need to provide the `website` argument'

  def zone = opts['website']
  def record = opts['record']

  if (record) {
    def full = [record, zone].join('.')
    def reg = ~/^_dnslink./
    return full - reg
  } else {
    return zone
  }
}

def call(opts = []) {
  def hashToPin
  def nodeIP
  def nodeMultiaddr
  def websiteHash
  def previousWebsiteHash

  def githubOrg
  def githubRepo
  def gitCommit

  assert opts['website'] : "You need to pass in zone as the `website` argument "
  assert opts['record'] : "You need to pass in name of the record as the `record` argument "

  def website = opts['website']
  def record = opts['record']
  def buildDirectory = './public'
  def disablePublish = false

  if (opts['build_directory']) {
    buildDirectory = opts['build_directory']
  }

  if (opts['disable_publish']) {
    disablePublish = opts['disable_publish']
  }

  stage('build website') {
      node(label: 'linux') {
          // Figure out our IP + Multiaddr for master to ensure connection
          nodeIP = sh returnStdout: true, script: 'dig +short myip.opendns.com @resolver1.opendns.com'
          nodeMultiaddr = sh returnStdout: true, script: "ipfs id --format='<addrs>\n' | grep $nodeIP"
          def details = checkout scm

          // Parse Github Org + Repo
          def origin = details.GIT_URL
          def splitted = origin.split("[./]")
          githubOrg = splitted[-3]
          githubRepo = splitted[-2]

          // Get commit from branch OR latest commit in PR
          def isPR = "$BRANCH_NAME".startsWith('PR-')
          if (isPR) {
              gitCommit = sh returnStdout: true, script: "git rev-parse remotes/origin/$BRANCH_NAME"
          } else {
              gitCommit = details.GIT_COMMIT
          }

          // Get the resolvable domain for "ipfs name resolve"
          resolvableDomain = Resolve(opts)

          // Get the current hash of the website
          currentHash = sh(returnStdout: true, script: "ipfs name resolve /ipns/$resolvableDomain || true").trim()

          // Get the list of previous versions if it exists
          sh "ipfs get $currentHash/_previous-versions > _previous-versions || true"

          // Build Website
          sh 'docker run -i -v `pwd`:/site ipfs/ci-websites make -C /site build'

          // Add the website to IPFS
          currentWebsite = sh(returnStdout: true, script: "ipfs add -rQ $buildDirectory").trim()

          // Add the link to the _previous-versions with $currentHash
          versionsHash = sh(returnStdout: true, script: "ipfs add -Q ./_previous-versions").trim()
          websiteHash = sh(returnStdout: true, script: "ipfs object patch $currentWebsite add-link _previous-versions $versionsHash").trim()

          // If previousHash (currently deployed) is same as websiteHash, we can skip
          if (currentHash == '/ipfs/' + websiteHash) {
              currentBuild.result = hudson.model.Result.SUCCESS.toString()
              println "This build is already the latest and deployed version"
              return
          }

          // Now we just have to add the previous link before
          sh "echo $currentHash >> ./_previous-versions"
          versionsHash = sh(returnStdout: true, script: "ipfs add -Q ./_previous-versions").trim()
          websiteHash = sh(returnStdout: true, script: "ipfs object patch $websiteHash add-link _previous-versions $versionsHash").trim()
          cleanWs notFailBuild: true
      }
  }
  stage('pin + publish preview + publish dns record update') {
    if (currentBuild.result == hudson.model.Result.SUCCESS.toString()) {
      println "This build is already the latest and deployed version"
      return
    }
    node(label: 'master') {
        withEnv(["IPFS_PATH=/efs/.ipfs"]) {
            sh "ipfs swarm connect $nodeMultiaddr"
            sh "ipfs pin add --progress $websiteHash"
        }
        def websiteUrl = "https://ipfs.io/ipfs/$websiteHash"
        sh "set +x && curl -X POST -H 'Content-Type: application/json' --data '{\"state\": \"success\", \"target_url\": \"$websiteUrl\", \"description\": \"A rendered preview of this commit\", \"context\": \"Rendered Preview\"}' -H \"Authorization: Bearer \$(cat /tmp/userauthtoken)\" https://api.github.com/repos/$githubOrg/$githubRepo/statuses/$gitCommit"
        echo "New website: $websiteUrl"
        if ("$BRANCH_NAME" == "master" && !disablePublish) {
          sh 'wget https://ipfs.io/ipfs/QmRhdziJEm7ZaLBB3H7XGcKF8FJW6QpAqGmyB2is4QVN4L/dnslink-dnsimple -O dnslink-dnsimple'
          sh 'chmod +x dnslink-dnsimple'
          token = readFile '/tmp/dnsimpletoken'
          token = token.trim()
          withEnv(["DNSIMPLE_TOKEN=$token"]) {
              sh "./dnslink-dnsimple $website /ipfs/$websiteHash $record"
          }
        }
    }
  }
}

