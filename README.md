---
title: Proxy service
keywords: 
last_updated: October 24, 2023
tags: []
summary: "Detailed description of how the proxy service works and its configuration."
---

## Overview

This service proxies an service running in your local box with your app in the SLINGR platform.
It is meant for development of services. See [Create your own service]({{site.baseurl}}/extensions_create_your_own_services.html) 
for more information.

## Configuration

### Service URI

The service URI is where the external service and SLINGR find your local service. This must be a public URL 
(so you might need to configure port forwarding in your router) and points to your TCP port specified by 
`_webservices_port` (`TCP 10000` by default) or more convenient, use [ngrok](https://ngrok.com/) which will open
a secure tunnel to your localhost.

### Service token

This is a token that will be used to verify messages coming from and to the proxy service are
valid. We suggest the auto-generated token, but you can change it if you want. 

This token will be in your local service configuration (see below).

### Configuration

This is the basic configuration you should use in your local service. Check the SDK you are using
to know where this has to be copied.

## Javascript API

The Javascript API will be defined by your service's functions and scripts. Please see
[Service features]({{site.baseurl}}/extensions_common_features.html) to understand what can be done.

## Events

Events will be defined in your service's configuration file. Please see 
[Service features]({{site.baseurl}}/extensions_common_features.html) for more information.

## About SLINGR

SLINGR is a low-code rapid application development platform that accelerates development, with robust architecture for integrations and executing custom workflows and automation.

[More info about SLINGR](https://slingr.io)

## License

This service is licensed under the Apache License 2.0. See the `LICENSE` file for more details.

