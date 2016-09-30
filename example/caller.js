'use strict'

const sugoCaller = require('sugo-caller')
const co = require('co')
const asleep = require('asleep')

const HUB = process.env.HUB || 'http://localhost:8080'
const ACTOR = process.env.ACTOR || 'qq:hitoe:1'

co(function * () {
  var caller
  var actor

  while (true) {
    try {
      caller = sugoCaller(HUB + '/callers')
      actor = yield caller.connect(ACTOR)
      break
    } catch (e) {
      console.log('no target actor')
    }
    yield asleep(3000)
  }

  const hitoe = actor.get('hitoe')
  hitoe.on('warning', (data) => console.log('warning: ' + data.heartRate + '@' + data.location))
  hitoe.on('emergency', (data) => console.log('emergency: ' + data.heartRate + '@' + data.location))
}).catch((err) => console.error(err))
