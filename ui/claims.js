import {controller} from './partnerView.js';

Vue.component('claims', {
    props: ['claims'],
    subscriptions: function() {
        return {
            entities: this.claims.entities$
        }
    },
    template: `
        <div id="claims" class="tile-group">
            Claims<br>
            <claim-form />
            <div v-if="claims.error" class="row">
                <q-alert type="warning" class="q-mb-sm" icon="priority_high">
                    {{claims.error}}
                </q-alert>
            </div>
            <div v-else-if="claims.loading" class="row"><q-spinner-hourglass size="32px"/></div>
            <div v-else-if="entities.length === 0" class="row"><i>No claims</i></div>
            <div v-else class="row">
                <div v-for="claim in entities" class="col-xs-12 col-sm-12 col-md-12 col-lg-6">
                    <div class="tile">
                        <div class='tile-title'><i class='fas fa-exclamation-circle'></i>&nbsp;Claim</div>
                        <div v-if="claim.temp" class='tile-body'><i>in progress...</i><br>{{claim.description}}</div>
                        <div v-else class='tile-body'><i>{{claim.id}}</i><br>{{claim.description}}</div>
                    </div>
                </div>
            </div>
        </div>
    `
});

Vue.component('claim-form', {
    data: () => {
        return  {
            form: {
                description: ""
            },
            showingNewclaims: false
        }
    },
    validations: {
        form: {
            description: {
                required: validators.required,
                minLength: validators.minLength(4),
                maxLength: validators.maxLength(40)
            }
        }
    },
    methods: {
        createClaim: function() {
            this.$v.form.$touch();
            if (this.$v.form.$error) {
                this.$q.notify("Please review fields again");
            } else {
                controller.createClaim(this.form.description);
                this.showingNewclaims = false;
                this.form.description = "";
                this.$v.$reset();
            }
        }
    },
    template: `
            <div class="row" style="margin: 10px;">
                <q-btn v-if="!showingNewclaims" label="create new claim..." color="primary" icon="create" @click="showingNewclaims = true"/>
                <q-card v-else style="width: 100%;">
                    <q-card-main>
                        <div class="row">
                            <q-input
                                class="col-8"
                                v-model="form.description"
                                type="textarea"
                                float-label="Description"
                                rows="4"
                                @blur="$v.form.description.$touch"
                                :error="$v.form.description.$error"
                            />
                            <div class="col-4 error" v-if="$v.form.description.$dirty && !$v.form.description.required">Description is required</div>
                            <div class="col-4 error" v-else-if="$v.form.description.$dirty && !$v.form.description.minLength">Description must have at least {{$v.form.description.$params.minLength.min}} letters</div>
                            <div class="col-4 error" v-else-if="$v.form.description.$dirty && !$v.form.description.maxLength">Description must have at most {{$v.form.description.$params.maxLength.max}} letters</div>
                        </div>
                        <div class="row">
                            <q-btn label="create" color="primary" @click="createClaim()" style="margin: 10px;"/>
                            <q-btn label="cancel" color="secondary" @click="showingNewclaims = false" style="margin: 10px;"/>
                        </div>
                    </q-card-main>
                </q-card>
            </div>
    `
});
