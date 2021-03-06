/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import "ABI25_0_0RCTSurfaceBackedComponent.h"

#import <UIKit/UIKit.h>

#import <ComponentKit/CKComponentSubclass.h>
#import <ComponentKit/CKOverlayLayoutComponent.h>
#import <ABI25_0_0RCTSurfaceHostingComponent/ABI25_0_0RCTSurfaceHostingComponent.h>
#import <ReactABI25_0_0/ABI25_0_0RCTSurface.h>

#import "ABI25_0_0RCTSurfaceBackedComponentState.h"

@implementation ABI25_0_0RCTSurfaceBackedComponent

+ (id)initialState
{
  return [ABI25_0_0RCTSurfaceBackedComponentState new];
}

+ (instancetype)newWithBridge:(ABI25_0_0RCTBridge *)bridge
                   moduleName:(NSString *)moduleName
                   properties:(NSDictionary *)properties
                      options:(ABI25_0_0RCTSurfaceHostingComponentOptions)options
{
  CKComponentScope scope(self, moduleName);

  ABI25_0_0RCTSurfaceBackedComponentState *state = scope.state();

  if (state.surface == nil || state.surface.bridge != bridge || ![state.surface.moduleName isEqualToString:moduleName]) {
    ABI25_0_0RCTSurface *surface =
      [[ABI25_0_0RCTSurface alloc] initWithBridge:bridge
                              moduleName:moduleName
                       initialProperties:properties];

    state = [ABI25_0_0RCTSurfaceBackedComponentState newWithSurface:surface];

    CKComponentScope::replaceState(scope, state);
  }
  else {
    if (![state.surface.properties isEqualToDictionary:properties]) {
      state.surface.properties = properties;
    }
  }

  ABI25_0_0RCTSurfaceHostingComponent *surfaceHostingComponent =
    [ABI25_0_0RCTSurfaceHostingComponent newWithSurface:state.surface
                                       options:options];

  CKComponent *component;
  if (options.activityIndicatorComponentFactory == nil || ABI25_0_0RCTSurfaceStageIsRunning(state.surface.stage)) {
    component = surfaceHostingComponent;
  } else {
    component = [CKOverlayLayoutComponent newWithComponent:surfaceHostingComponent
                                                   overlay:options.activityIndicatorComponentFactory()];
  }

  return [super newWithComponent:component];
}

@end
