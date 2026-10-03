<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request, errorText } from '../api'
const props=defineProps<{orderId:string}>()
const shipment=ref<{status:string;carrier:string;trackingNumber:string;shippedAt:string}>(),error=ref('')
async function load(){error.value='';try{shipment.value=await request('GET',`/orders/${props.orderId}/shipment`)}catch(e){error.value=errorText(e)}}
onMounted(load)
</script>
<template><div class="shipment"><p v-if="shipment?.status==='SHIPPED'">已登记发货 · {{ shipment.carrier }} · {{ shipment.trackingNumber }}<small>{{ new Date(shipment.shippedAt).toLocaleString('zh-CN') }}</small></p><p v-else-if="shipment">尚未登记发货</p><p v-if="error" role="alert">{{ error }} <button @click="load">重试</button></p></div></template>
<style scoped>.shipment{padding:0 28px;font-size:13px;color:var(--wood)}small{display:block;margin-top:6px}</style>
