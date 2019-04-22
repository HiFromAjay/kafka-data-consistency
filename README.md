# kafka-data-consistency

Experiement with using Kafka and idempotency to garantee data consistency by creating a reactive asynchronous system.

## Installing Kafka

Kafka needs to be present to build a suitable docker image.

    wget https://www-eu.apache.org/dist/kafka/2.1.1/kafka_2.11-2.1.1.tgz
    tar -xzf kafka_2.11-2.1.1.tgz
    #echo kafka_2.11-2.1.1 >> .gitignore
    rm kafka_2.11-2.1.1.tgz
    #git init

## Kubernetes

If necessary use the minikube docker host:

    eval $(minikube docker-env)

Run `./build.sh` after getting Kafka (see above).

Create a namespace:

    kubectl create -f namespace.json

Delete existing, if necessary:

    kubectl -n kafka-data-consistency delete deployment zookeeper
    kubectl -n kafka-data-consistency service deployment zookeeper
    kubectl -n kafka-data-consistency delete deployment kafka-1
    kubectl -n kafka-data-consistency service deployment kafka-1
    kubectl -n kafka-data-consistency delete deployment kafka-2
    kubectl -n kafka-data-consistency service deployment kafka-2
    kubectl -n kafka-data-consistency service deployment elasticsearch

Create deployments and services:

    kubectl -n kafka-data-consistency apply -f zookeeper.yaml
    kubectl -n kafka-data-consistency apply -f kafka-1.yaml
    kubectl -n kafka-data-consistency apply -f kafka-2.yaml
    kubectl -n kafka-data-consistency apply -f elasticsearch.yaml

Open ports and set up forwing like this:

    firewall-cmd --zone=public --permanent --add-port=30000/tcp
    firewall-cmd --zone=public --permanent --add-port=30001/tcp
    firewall-cmd --zone=public --permanent --add-port=30002/tcp
    firewall-cmd --zone=public --permanent --add-port=30050/tcp
    firewall-cmd --zone=public --permanent --add-port=30051/tcp
    firewall-cmd --reload
    firewall-cmd --list-all

    socat TCP-LISTEN:30000,fork TCP:$(minikube ip):30000 &
    socat TCP-LISTEN:30001,fork TCP:$(minikube ip):30001 &
    socat TCP-LISTEN:30002,fork TCP:$(minikube ip):30002 &
    socat TCP-LISTEN:30050,fork TCP:$(minikube ip):30050 &
    socat TCP-LISTEN:30051,fork TCP:$(minikube ip):30051 &


    #firewall-cmd --zone=public --permanent --add-port=30000/tcp

Create topics (on minikube host):

    kafka_2.11-2.1.1/bin/kafka-topics.sh --create --zookeeper $(minikube ip):30000 --replication-factor 2 --partitions 4 --topic claim-create-command
    kafka_2.11-2.1.1/bin/kafka-topics.sh --create --zookeeper $(minikube ip):30000 --replication-factor 2 --partitions 4 --topic task-create-command
    kafka_2.11-2.1.1/bin/kafka-topics.sh --create --zookeeper $(minikube ip):30000 --replication-factor 2 --partitions 4 --topic claim-created-event
    kafka_2.11-2.1.1/bin/kafka-topics.sh --create --zookeeper $(minikube ip):30000 --replication-factor 2 --partitions 4 --topic task-created-event
    kafka_2.11-2.1.1/bin/kafka-topics.sh --list --zookeeper $(minikube ip):30000

Create Elasticsearch index:

    curl -X DELETE "maxant.ch:30050/claims"

    curl -X PUT "maxant.ch:30050/claims" -H 'Content-Type: application/json' -d'
    {
        "settings" : {
            "index" : {
                "number_of_shards" : 1,
                "number_of_replicas" : 1
            },
            "analysis": {
                "filter": {
                    "english_stop": {
                        "type":       "stop",
                        "stopwords":  "_english_"
                    },
                    "english_stemmer": {
                        "type":       "stemmer",
                        "language":   "english"
                    },
                    "german_stop": {
                        "type":       "stop",
                        "stopwords":  "_german_"
                    },
                    "german_stemmer": {
                        "type":       "stemmer",
                        "language":   "light_german"
                    },
                    "french_elision": {
                        "type":         "elision",
                        "articles_case": true,
                        "articles": [
                          "l", "m", "t", "qu", "n", "s",
                          "j", "d", "c", "jusqu", "quoiqu",
                          "lorsqu", "puisqu"
                        ]
                    },
                    "french_stop": {
                        "type":       "stop",
                        "stopwords":  "_french_"
                    },
                    "french_stemmer": {
                        "type":       "stemmer",
                        "language":   "light_french"
                    }
                },
                "analyzer": {
                    "ants_analyzer": {
                        "tokenizer": "standard",
                        "filter": [
                            "lowercase",
                            "english_stop",
                            "english_stemmer",
                            "german_stop",
                            "german_normalization",
                            "german_stemmer",
                            "french_elision",
                            "french_stop",
                            "french_stemmer"
                        ]
                    }
                }
            }
        },
        "mappings" : {
            "properties": {
                "customerId": { "type": "keyword" },
                "summary": { "type": "text", "analyzer": "ants_analyzer" },
                "description": { "type": "text", "analyzer": "ants_analyzer" },
                "reserve": { "type": "double" },
                "date": { "type": "date", "format": "strict_date" }
            }
        }
    }
    '

E.g. run task service locally, but connecting to kube:

    java -Xmx64M -Xms64M -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8787 -Dkafka.bootstrap.servers=maxant.ch:30001,maxant.ch:30002 -jar web/target/web-microbundle.jar --port 8080 &
    java -Xmx64M -Xms64M -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8788 -Dkafka.bootstrap.servers=maxant.ch:30001,maxant.ch:30002 -jar claims/target/claims-microbundle.jar --port 8081 &
    java -Xmx64M -Xms64M -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8789 -Dkafka.bootstrap.servers=maxant.ch:30001,maxant.ch:30002 -jar tasks/target/tasks-microbundle.jar --port 8082 &

Useful Kube stuff:

    kubectl describe nodes

    # restart kube entirely, deleting everything
    minikube stop
    rm -rf ~/.minikube
    minikube delete
    minikube config set vm-driver kvm2
    minikube start --memory 8192 --cpus 4
    git clone https://github.com/kubernetes-incubator/metrics-server.git
    cd metrics-server/
    kubectl create -f deploy/1.8+/
    minikube addons enable metrics-server
    minikube dashboard &

    # connect to the vm. eg. top and stuff to see whats going on inside.
    minikube ssh

    # create a deployment
    kubectl run hello-minikube --image=k8s.gcr.io/echoserver:1.10 --port=8080
    # create a service from a deployment
    kubectl expose deployment hello-minikube --type=NodePort

## Starting Kafka with docker

WARNING: this section may be slightly out of date!

The generated `maxant/kafka` image uses a shell script to append important properties to the
`config/server.properties` file in the container, so that it works.
See `start-kafka.sh` for details, including how it MUST set
`advertised.listeners` to the containers IP address, otherwise kafka has
REAL PROBLEMS.

Run `./build.sh` which builds images for Zookeeper and Kafka

Run `./run.sh` which starts Zookeeper and two Kafka brokers with IDs 1 and 2,
listening on ports 9091 and 9092 respectively.

This script also shows how to append the hosts file so that service names can
be used by applications, but it requires the user to `sudo`. It also contains
examples of waiting for Zookeeper / Kafka logs to contain certain logs before
the script continues.


## Debug Web Component

    java -Ddefault.property=asdf -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8787 -jar web/target/web-microbundle.jar

## Hot deploy with Payara

Instead of deploying the built WAR file, Payara allows you to start with an exploded folder. Maven happens to build one
during the package phase. You can redeploy by touching the `.reload` file.
So, deploy like this, e.g. the web component:

    java ... -jar <path to payara-micro-5.184.jar> --deploy web/target/web

Or in full:

    java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8787 -Dkafka.bootstrap.servers=172.17.0.4:9092,172.17.0.3:9092 -jar /home/ant/.m2/repository/fish/payara/extras/payara-micro/5.184/payara-micro-5.184.jar --deploy web/target/web

Then you can build and redeploy as follows, e.g. the web component:

    mvn -pl web package && touch web/target/web/.reload

More info: https://docs.payara.fish/documentation/payara-micro/deploying/deploy-cmd-line.html

# Links

- Microprofile Specs: https://github.com/eclipse/microprofile-health/releases/tag/1.0
- Microprofile POMs, etc.: https://github.com/eclipse/microprofile
- Payara Docs: https://docs.payara.fish/documentation/microprofile/
- Payara Examples: https://github.com/payara/Payara-Examples/tree/master/microprofile

# TODO

- add context of "partner" to filter on websocket server side
- finish build and run scripts
- dockerize ui, tasks, claims, web
- add image for neo4j, orientdb, ES, kibana?

create => 1x createTask, 1xCreateRelationShips

hmmm think transactions. need to think how not to lose call to websocket when writing to neo4j or orientdb.

- ES
  - set index types in ES?
- UI
  - use resolver to avoid async code => except eg using an observable for updating server auto complete
      - example with addresses from post.ch
  - add claim page to view details of a claim
  - validation up front with http. if not available, then temp tile looks different => validation error should
    then be given to user as a task for them to fix
  - add aggregate for related claims, so we can show prototype of aggregated data
  - add search screen based on ES
  - see TODOs inside UI component
  - what are Vue.compile, extend, mixin, util?
  - https://www.codeinwp.com/blog/vue-ui-component-libraries/ => quasar
  - useful link for filters: https://vuejs.org/v2/guide/filters.html
  - useful link for flex: https://css-tricks.com/snippets/css/a-guide-to-flexbox/
  - useful link for validation: https://vuelidate.netlify.com/
  - useful link for vue-rxjs: https://github.com/vuejs/vue-rx
  - useful link for rxjs: https://www.learnrxjs.io/operators/creation/from.html
  - useful tip: console.dir(document.getElementById("id")) => shows an object rather than the rendered element
  - useful tip: document.getElementById("claims-form-other").__vue__ => gets the vue component
  - useful tip: document.getElementById("claims-form-other").__vue__.$refs.input.focus() => set focus on it. not sure this is the correct way to do it tho!
  - useful tip: document.getElementById("claims-form-other").__vue__.rules.map(function(f){return f(document.getElementById("claims-form-other").__vue__.$refs.input.value)}) => execute all internal validation rules on the component
  - my question: https://forum.quasar-framework.org/topic/3391/how-can-i-hide-a-column
  - my question: https://forum.quasar-framework.org/topic/3437/unit-testing-and-simulating-input
  - my question: https://forum.quasar-framework.org/topic/3438/form-validation
- example of error messages and e.g. security exceptions via error messages
- fixme consumer.seekToEnd(asList(new TopicPartition(TASK_CREATED_EVENT_TOPIC, 0), new TopicPartition(CLAIM_CREATED_EVENT_TOPIC, 0)));
- add ES for search
- add kibana on top of ES?
- Tests with running server: https://groups.google.com/forum/#!topic/payara-forum/ZSRGdPkGKpE
  - starting server: https://blog.payara.fish/using-the-payara-micro-maven-plugin
  - https://docs.payara.fish/documentation/ecosystem/maven-plugin.html
- add extra jars to uberjar: https://blog.payara.fish/using-the-payara-micro-maven-plugin
- orientdb docker image => https://hub.docker.com/_/orientdb
- define payara config with yml
- add https://docs.payara.fish/documentation/microprofile/healthcheck.html and use it in start script?
- https://blog.payara.fish/using-hotswapagent-to-speed-up-development => [hotswapagent.md](hotswapagent.md)

# TODO Blog

- need lock when using transactional kafka, but not otherwise since producer is thread safe


- objective: use a light weight UI technology that does not require us to have a build system
  - assume http/2 so that multiple fetches don't worry us
  - need lazy loading => see example in boot.js

- vuex says "Vuex uses a single state tree - that is, this single object contains all your application level state and serves as the "single source of truth". This also means usually you will have only one store for each application." (https://vuex.vuejs.org/guide/state.html)
- it also says "So why don't we extract the shared state out of the components, and manage it in a global singleton? With this, our component tree becomes a big "view", and any component can access the state or trigger actions, no matter where they are in the tree!" (https://vuex.vuejs.org/)
- thats a conflict :-)
- compare http://blog.maxant.co.uk/pebble/2008/01/02/1199309880000.html and http://blog.maxant.co.uk/pebble/images/ants_mvc.jpg with https://github.com/facebook/flux/tree/master/examples/flux-concepts and https://github.com/facebook/flux/blob/master/examples/flux-concepts/flux-simple-f8-diagram-with-client-action-1300w.png
- vuex also doesn't match what flux says: "There should be many stores in each application." (https://github.com/facebook/flux/tree/master/examples/flux-concepts)
- see video here: https://facebook.github.io/flux/ at 11:03 (screen shot at ./ui/mvc-incorrect-as-far-as-ant-thinks.png) (https://www.youtube.com/watch?list=PLb0IAmt7-GS188xDYE-u1ShQmFFGbrk0v&time_continue=659&v=nYkdrAPrdcw)
- i believe that the arrows going from view back to model are a result of the "separable model architecture" well known in java swing.
- "So we collapsed these two entities (view and controller) into a single UI object" (https://www.oracle.com/technetwork/java/architecture-142923.html) => that leads to arrows as shown where the view directly updates the model. it is also the problem with two way binding (https://stackoverflow.com/questions/38626156/difference-between-one-way-binding-and-two-way-binding-in-angularjs)
- don't two-way-bind WITH THE STORE. instead, get forms to fill their own model, and pass that to the CONTROLLER to merge the changes into the main model
- to ensure that you only write to the model inside the controller
- this can be inforced by only importing the model in the main components which
  create their controllers, e.g. the `PartnerView`:

    import {model} from './model.js';
    import {Store} from './store.js';
    import {Controller} from './controller.js';

    const store = new Store(model);
    const controller = new Controller(store, model);

- why doesnt vue have dependency injection? not really needed. or maybe when we go to test? defo then! or maybe not,
  see example with "mocks"
  - WRONG - it does exist. see `provide` and `inject` in `claims.js` and `partnerView.js`. mocking works then, see `claims.spec.js`.

- observables => show example of the subscription to claims inside claims.js and
  how the template treats it as an object. v-for works as expected, but the thing
  is actually an observable. then show how we add to it by calling getValue in
  controller, using a BehaviourSubject. and we pass values from axios promise
  to a service obserable but manually put them into the subject by calling next. can we improve that?

- async route loading via custom loader since vue supports creating routes with promises

# Useful Elasticsearch stuff:

- Info about indices

    curl -X GET "maxant.ch:30050/_cat/indices?v"

    health status index  uuid                   pri rep docs.count docs.deleted store.size pri.store.size
    yellow open   claims 5SY5zm3bRxCgeTAac4wazQ   1   1          1            0      6.2kb          6.2kb

- Create and index a document:

    curl -X PUT "maxant.ch:30050/claims/_doc/b5565b5c-ab65-4e00-b562-046e0d5bef70?pretty" -H 'Content-Type: application/json' -d'
    {
        "id" : "b5565b5c-ab65-4e00-b562-046e0d5bef70",
        "summary" : "the cat ate the frog",
        "description" : "L\u0027avion volé à Paris mit 2 Hünde an bord, and arrived in two hours flat!",
        "customerId" : "C-1234-5678",
        "date" : "2018-08-01",
        "reserve" : 9100.05
    }
    '

- Pretty print a regexp query (note `?pretty`):

    curl -X GET "maxant.ch:30050/claims/_search?pretty" -H 'Content-Type: application/json' -d'{ "query" : { "regexp" : { "description" : ".*m\u0027avion.*" } } }'

NOTE that a regexp query does not use analyzers!!

- field types: https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping.html

- analyse an analyzer:

    curl -X POST "maxant.ch:30050/claims/_analyze?pretty" -H 'Content-Type: application/json' -d'
    {
      "analyzer": "ants_analyzer",
      "text": "L\u0027avion volé à Paris mit 2 Hünde an bord, and arrived in two hours flat!"
    }
    '

- Or to analyse how a field might match:

    curl -X POST "maxant.ch:30050/claims/_analyze?pretty" -H 'Content-Type: application/json' -d'
    {
      "field": "description",
      "text": "arrives"
    }
    '

- Try these two matches queries:

    curl -X GET "maxant.ch:30050/claims/_search?pretty" -H 'Content-Type: application/json' -d'{ "query" : { "match" : { "description" : "*arrives*" } } }'
    curl -X GET "maxant.ch:30050/claims/_search?pretty" -H 'Content-Type: application/json' -d'{ "query" : { "match" : { "description" : "m\u0027avion" } } }'

The first matches even though the record contains "arrived". The second matches `m'avion` rather than `L'avion` in the document.

- keyword query:

    curl -X GET "maxant.ch:30050/claims/_search?pretty" -H 'Content-Type: application/json' -d'{ "query" : { "match" : { "customerId" : "C-1234-5678" } } }'

- count:

    curl -X GET "maxant.ch:30050/claims/_count?pretty" -H 'Content-Type: application/json' -d'{ "query" : { "match" : { "customerId" : "C-1234-5678" } } }'

- range query:

    curl -X GET "maxant.ch:30050/claims/_search?pretty" -H 'Content-Type: application/json' -d'{ "query" : { "range" : { "reserve" : {"gt": 9000 } } } }'

## Links

- ES 7.0 REST High Level Client Javadocs: https://artifacts.elastic.co/javadoc/org/elasticsearch/client/elasticsearch-rest-high-level-client/7.0.0/index-all.html
- Generic Reference: https://www.elastic.co/guide/en/elasticsearch/reference/current/getting-started-query-document.html
- Java Client: https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/java-rest-high-count.html

