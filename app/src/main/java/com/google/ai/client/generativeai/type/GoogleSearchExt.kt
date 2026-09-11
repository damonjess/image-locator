package com.google.ai.client.generativeai.type

class GoogleSearch

fun googleSearch(): GoogleSearch = GoogleSearch()

fun Tool(googleSearch: GoogleSearch): Tool = Tool()
