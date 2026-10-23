"use strict";
const fs=require('node:fs'),path=require('node:path');
const {inside,verifySlot,requireThat}=require('./manifest.cjs');
function emptySelection(){return {schemaVersion:1,active:null,previous:null,verified:null,revision:0};}
function validateSelection(s){requireThat(s&&s.schemaVersion===1&&Number.isSafeInteger(s.revision)&&s.revision>=0,'Invalid selection');for(const k of ['active','previous','verified'])if(s[k]!==null){const p=s[k];requireThat(p&&typeof p.id==='string'&&/^[a-z0-9][a-z0-9._-]{0,95}$/.test(p.id)&&!['.','..'].includes(p.id)&&/^[a-f0-9]{64}$/.test(p.manifestSha256),'Invalid pin');}return s;}
// Pure transitions. The Android app owns persistence under a FileChannel OS lock.
function transition(s,action,{pin,dataSchema,activeDataSchema,expectedRevision,serviceStopped,explicitlyConfirmed}={}){
 validateSelection(s);requireThat(s.revision===expectedRevision,'Selection revision changed');
 if(action==='activate'){requireThat(serviceStopped===true,'Stop before activation');validateSelection({...emptySelection(),active:pin});requireThat(!s.active||dataSchema===activeDataSchema,'Explicit migration required');return {...s,active:pin,previous:s.active,revision:s.revision+1};}
 if(action==='verify'){requireThat(s.active&&explicitlyConfirmed===true,'Transport alone is not verified');return {...s,verified:s.active,revision:s.revision+1};}
 if(action==='rollback'){requireThat(serviceStopped===true&&s.previous,'Stop before rollback; previous slot required');requireThat(pin&&pin.id===s.previous.id&&pin.manifestSha256===s.previous.manifestSha256,'Previous runtime changed');requireThat(dataSchema===activeDataSchema,'Cannot reverse data formats');return {...s,active:s.previous,previous:s.active,revision:s.revision+1};}
 throw Error('Unknown action');
}
class SlotStore{
 constructor(stateRoot,slotsRoot){this.file=path.join(stateRoot,'selection.json');this.slotsRoot=slotsRoot;}
 read(){return fs.existsSync(this.file)?validateSelection(JSON.parse(fs.readFileSync(this.file,'utf8'))):emptySelection();}
 inspect(id,options){requireThat(typeof id==='string'&&/^[a-z0-9][a-z0-9._-]{0,95}$/.test(id)&&!['.','..'].includes(id),'Invalid id');const slot=verifySlot(inside(this.slotsRoot,id),options);requireThat(slot.manifest.id===id,'Slot id mismatch');return slot;}
 active(){const s=this.read();requireThat(s.active,'No selected runtime');const slot=this.inspect(s.active.id,{full:false});requireThat(slot.manifestSha256===s.active.manifestSha256,'Selected manifest changed');return {...slot,selection:s};}
}
module.exports={SlotStore,emptySelection,validateSelection,transition};
