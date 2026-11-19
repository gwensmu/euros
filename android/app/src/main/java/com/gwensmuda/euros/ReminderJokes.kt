package com.gwensmuda.euros

import kotlin.random.Random

/**
 * Optional weasel lines appended to a reminder's fixed facts. Chosen on the phone at delivery,
 * at random, avoiding recently delivered lines; no network or model is involved.
 *
 * Every line is about the weasel or the moment, never a claim about the athlete or their plan,
 * so any line fits any reminder of its kind. The skip line is the one exception: it is offered
 * only when the reminder's facts already cite an explicit logged skip (allowSkipJoke).
 */
object ReminderJokes {
 data class Joke(val id:String,val text:String)

 private fun lines(vararg pairs:Pair<String,String>)=pairs.map{Joke(it.first,it.second)}

 // Kinds match RichReminder.kind. Sunday mornings draw from both morning and sunday lines.
 val catalog:Map<String,List<Joke>> = mapOf(
  "morning" to lines(
   "stove" to "I'd help with breakfast, but I'm not allowed near the stove.",
   "paws" to "I've got the plan. You're in charge of anything requiring opposable thumbs.",
   "dawn" to "I've been up since dawn. Weasels are terrible at sleeping in.",
   "counter" to "Coffee is your department. I can't reach the counter.",
   "reviewed" to "Today's plan has been reviewed by a weasel. Standards remain unclear.",
   "read_twice" to "I read today's plan twice. Once upside down, by accident.",
  ),
  "sunday" to lines(
   "scale" to "My own weigh-in involves a kitchen scale and is none of your business.",
   "clipboard" to "I've borrowed a clipboard. It makes me look taller.",
   "sunday_gi" to "I've pressed my Sunday gi. It took four seconds.",
  ),
  "weight" to lines(
   "too_small" to "I'd weigh in too, but the scale doesn't register anything my size.",
   "understanding" to "The scale and I have an understanding. It doesn't talk about me.",
   "look_away" to "Shoes off, phone down. I'll look away politely.",
   "quick" to "Ten seconds on the scale. I've timed slower blinks.",
   "opinions" to "Just the number, please. I'll keep my opinions to myself. Mostly.",
  ),
  "training" to lines(
   "bag" to "I'd pack your bag, but I'd mostly fill it with snacks.",
   "gi" to "My gi is ready. Admittedly, it takes very little fabric.",
   "bottle" to "I'll guard the water bottle. Nobody gets past me.",
   "theoretical" to "I'd come along, but I'm a strictly theoretical athlete.",
   "coach" to "If anyone asks, I'm your coach. Please don't let anyone ask.",
   "stretched" to "I've stretched. Mostly my imagination, but it counts.",
  ),
  "recovery" to lines(
   "lying_down" to "Finally, a part of the plan that appreciates lying down.",
   "short_legs" to "My legs are already quite close to the floor. Yours may need more arranging.",
   "foam_roller" to "I'll hold the foam roller. Emotionally.",
   "slow_part" to "Take it slow. This is the part where nobody gets squished.",
   "natural" to "Stretching is just lying down with intent. I'm a natural.",
  ),
  "bedtime" to lines(
   "burrow" to "I'll be in my burrow. Leave the light on if you're still stretching.",
   "pajamas" to "I've already put on my tiny pajamas.",
   "pillow" to "A few checkboxes stand between you and the pillow. I'm rooting for the pillow.",
   "lights" to "Last call before lights out. Weasels are early sleepers, whatever you've heard.",
  ),
  "prep" to lines(
   "distance" to "I'll supervise the chopping from a safe distance.",
   "dishes" to "Batch cooking is meal planning with more dishes. I'm not doing the dishes.",
   "tidy" to "I can't read labels, but I appreciate a tidy fridge.",
   "taste_tester" to "I'm available as a taste tester. Purely for quality control.",
  ),
  "food" to lines(
   "accountant" to "Small paws, surprisingly strong opinions about arithmetic.",
   "snacks" to "I can do the paperwork. I'm not to be trusted with the snacks.",
   "auditor" to "Approximate is fine. I'm a weasel, not an auditor.",
   "crumbs" to "Crumbs count too. Ask me how I know.",
   "say_it" to "Type it the way you'd say it. I'll handle the arithmetic.",
  ),
  "water" to lines(
   "sips" to "Weasels drink in tiny sips. You're allowed bigger ones.",
   "lonely" to "Bring the water bottle to the table. It gets lonely.",
   "pour" to "I'd pour you a glass, but most of it would end up on me.",
   "commentary" to "The plate is planned. I just provide the commentary.",
  ),
 )
 private val skip=Joke("weasel_out","Try not to weasel out of this one.")

 fun options(kind:String,allowSkipJoke:Boolean,sunday:Boolean):List<Joke> =
  (catalog[kind].orEmpty()+(if(sunday && kind=="morning")catalog["sunday"].orEmpty() else emptyList())+
   (if(allowSkipJoke && kind=="training")listOf(skip) else emptyList()))

 /** A random line not among recent ids; when every option was used recently, the least recent. */
 fun choose(kind:String,allowSkipJoke:Boolean,sunday:Boolean,recent:List<String>,random:Random=Random.Default):Joke? {
  val options=options(kind,allowSkipJoke,sunday)
  if(options.isEmpty())return null
  val fresh=options.filter{it.id !in recent}
  if(fresh.isNotEmpty())return fresh.random(random)
  // recent is newest first; the option seen longest ago comes last.
  return options.maxBy{recent.indexOf(it.id)}
 }
}
